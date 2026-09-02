# Module Review: `session-host`

Date: 2026-09-02  
Status: reviewed in isolation

## Scope and coverage

This review covers the module's Maven and Cargo build definitions, Rust sources, protocol specification,
fixtures, module README, Unix integration tests, and module-specific history. It deliberately does not inspect
callers or implementations in other modules.

The review is static and read-only. Maven and Cargo verification were not run because review verification
belongs to the implementation workflow.

The module currently supports Linux as its production platform and macOS for local development. The Windows
implementation is an explicit unsupported stub and is not treated as a defect.

## Current conceptual model

`session-host` is a native process owner with five internal responsibilities:

1. Parse and validate a session command.
2. Start a child on a PTY and track its descendant process tree.
3. Serialize terminal and lifecycle events into an append-only journal.
4. publish a JSON metadata snapshot for discovery and status.
5. Serve control requests over a Unix-domain socket.

The main thread owns session completion. A PTY reader thread records output, an accept thread creates one
detached worker per control connection, and graceful termination creates detached timer threads. A single
`SharedState` mutex serializes journal writes, metadata updates, and most control state. A second mutex preserves
PTY input write order after the shared-state lock is released.

The observable metadata state is intended to be `starting`, `running`, `exited`, or `failed`. The implemented
happy-path transitions are only `starting -> running -> exited`. The journal and metadata both persist journal
identity and position information.

## Highest-value findings

### 1. Metadata is a synchronously maintained replica of journal facts

**Finding.** `metadata` is treated as a second durable source for journal identity and position. Keeping it
current forces a JSON temporary-file write and rename after every event, including every PTY output chunk.

**Evidence.** `Metadata` stores `journal_id`, `active_segment`, `oldest_available_timestamp`, and
`latest_timestamp` even though the segment headers and records contain the corresponding facts. `SharedState::append`
updates the timestamp snapshot, and `copy_pty_output` immediately calls `persist_metadata` after every read.
The journal record is written before the metadata replacement, so a crash or metadata I/O failure can leave the
two durable representations disagreeing.

**Why it likely exists.** The snapshot gives discovery and status readers quick access without scanning the
journal. That is useful, but the current contract makes the cache authoritative and immediately consistent.

**Simpler model.** Make the journal authoritative for its UUID, active segment, and timestamp bounds. Keep
metadata for launch configuration and lifecycle facts only. If fast discovery still needs journal bounds, label
them as a rebuildable snapshot and update them at coarse lifecycle checkpoints instead of after every event.

**Contract change.** Metadata would no longer guarantee immediately current journal bounds. A status reader
would query the live host or derive them from the journal. Cross-module reliance was intentionally not inspected.

**Consequences.** This removes per-output metadata rewrites, reduces global-lock hold time, and eliminates a
crash-consistency obligation. Cold status reads may require a journal header/index read.

**Confidence.** High that the duplication and write amplification exist; medium that consumers can accept the
weaker snapshot contract.

### 2. Lifecycle concepts disagree and failure has no owner

**Finding.** Metadata state, `child_live`, the recorded child PID, and descendant liveness represent overlapping
but different lifecycle concepts. The declared `failed` terminal state is never produced.

**Evidence.** Production code assigns only `Starting`, `Running`, and `Exited`. After the root child is reaped,
`child_live` remains true until every tracked descendant exits. The Unix integration test deliberately verifies
that the protocol's `child live` flag remains set after the recorded child PID has exited. Errors propagated after
the initial metadata write do not transition metadata to `Failed`; they simply make `main` exit with code 70.

**Why it likely exists.** The original child lifecycle grew into ownership of a whole process tree, while names
and protocol fields retained the earlier child-oriented vocabulary. Error paths were implemented independently
from the happy-path state transitions.

**Simpler model.** Use one observable session lifecycle: `starting -> active -> exited|failed`. Define activity
as an owned process tree being live. Keep root-process exit as a journal fact, not as the meaning of session
activity. Route every post-initialization exit through one finalization path that records either `exited` or
`failed` and performs cleanup.

**Contract change.** The status flag currently documented as `child live` becomes `owned process tree live`.
Either make `failed` a real guaranteed terminal transition or remove it from v1 rather than retaining an
unreachable state.

**Consequences.** Clients receive one coherent liveness definition, and error paths cannot silently leave
`starting` or `running` metadata behind. A finalization path must distinguish failures that occur before and
after the child starts.

**Confidence.** High.

### 3. The journal block layer promises capabilities that the writer and reader do not provide

**Finding.** The block abstraction currently adds duplicated framing and compatibility commitments without
providing batching or compression. Its `FINAL` guarantee is not implementable by the current write order.

**Evidence.** Every `JournalWriter::append_at` call writes one `NONE` block containing exactly one record, so
block count and first/last timestamps duplicate record data. The header is encoded with `FINAL` and written
before its payload, although the protocol says `FINAL` means the writer completed the block. The canonical
truncated-record fixture itself contains a `FINAL` header followed by a truncated payload. The protocol also says
v1 readers accept Zstandard, while a complete non-`NONE` block is rejected and the crate has no Zstandard
dependency.

**Why it likely exists.** Compression, block batching, segment rotation, and retention were reserved in the
format before the module needed them.

**Simpler model.** If v1 has not become a compatibility boundary, store validated records directly after the
segment header and add a block layer only when batching or compression is implemented. If v1 is already fixed,
keep the bytes but narrow the documented contract: codec `1` is reserved/unsupported and `FINAL` is not a
completion proof. Avoid implementing compression solely to justify the existing abstraction.

**Planned resolution.** The approved CBOR Sequence replacement removes block framing and `FINAL` entirely.
Its task and format contract explicitly prohibit introducing an equivalent persisted completion marker; a
complete CBOR item is the only record-completion boundary.

**Contract change.** Removing blocks breaks the binary format. Narrowing the documentation withdraws promised
Zstandard support and changes the meaning of `FINAL`. Persisted or external v1 consumers must be identified
before either change.

**Consequences.** A record-only format removes a header, a second checksum, duplicated counts/timestamps, and a
second parsing pass. It gives up predesigned compression and batching compatibility.

**Confidence.** High.

### 4. Detached concurrency is counted but not owned

**Finding.** Connection and termination threads have partial coordination that does not provide a clear shutdown
or idempotency guarantee.

**Evidence.** The accept loop creates an unbounded detached thread for each connection. `active_connections`
counts those threads, but shutdown only polls the count for one second; it cannot close, cancel, or join them.
Every accepted graceful `TERMINATE` creates another detached sleeping thread, so retries can create multiple
kill timers. Core operations still serialize through `SharedState`, limiting the throughput benefit of the
unbounded worker model.

**Why it likely exists.** Blocking Unix sockets and PTY writes make thread-per-operation implementation direct,
while the counter and grace wait were added later to reduce abrupt shutdown.

**Simpler model.** Keep blocking I/O, but own a bounded set of connection workers and one session termination
deadline. Make repeated termination requests update or reuse the one deadline. Either join workers during
shutdown or delete the counter and stop claiming a drain period.

**Contract change.** A worker bound can reject excessive simultaneous clients. Choosing to delete the drain
logic explicitly permits active control responses to be cut off at process exit; choosing ownership guarantees
their completion or cancellation.

**Consequences.** The module loses unbounded client concurrency but gains deterministic resource use,
idempotent termination, and a testable shutdown contract. No async framework is required.

**Confidence.** Medium because the expected maximum number of simultaneous clients is not documented locally.

### 5. The exact Rust toolchain version has multiple sources of truth

**Finding.** An upgrade must keep several declarations aligned, and one of them is unused.

**Evidence.** The exact version appears in `pom.xml` as `rust.version`, in `Makefile` as
`SESSION_HOST_RUST_VERSION`, and in `rust-toolchain.toml` as `channel`. The Maven property is not referenced by
the module's POM. `Cargo.toml` separately declares the compatible language/toolchain floor as `rust-version`.

**Why it likely exists.** Maven, direct Cargo development, and bootstrap installation each acquired their own
declaration.

**Simpler model.** Select one exact bootstrap pin and derive the other build entry points from it. Keep Cargo's
`rust-version` only as a compatibility floor if it intentionally has different semantics. At minimum, delete the
unused Maven property.

**Contract change.** None.

**Consequences.** Toolchain upgrades become one intentional edit plus a checksum update. Deriving a Make value
from TOML adds a small amount of build parsing, so simply making the Make variable authoritative may be smaller.

**Confidence.** High.

## Smaller contract inconsistencies

- Resolved: the protocol and journal plans now consistently require readers to expose structurally valid unknown
  event types as opaque records while allowing consumers to skip their semantic interpretation.
- `APPEND_EVENT` is documented as a v1 control request, but the host always returns
  `ERROR_UNSUPPORTED_MESSAGE`. If the number is only allocated for future use, document it as reserved rather
  than supported.

## Things to try deleting

- `journal_id`, `active_segment`, and timestamp bounds from authoritative metadata, once readers use journal
  facts or treat these fields as a cache.
- The block layer, codec enum, and truncated-Zstandard fixture if binary v1 compatibility is not yet required.
- `SessionState::Failed` if no durable failure state is required; otherwise make it reachable instead.
- `active_connections` and the one-second drain loop if the module does not promise connection draining.
- The unused Maven `rust.version` property.

## Proposed conceptual model

- One exact Rust bootstrap version source.
- Static session metadata plus one authoritative append-only journal.
- One session lifecycle based on ownership of the process tree, with a single finalization path.
- One bounded control-worker policy and one idempotent termination deadline.
- A protocol that distinguishes implemented messages/codecs from merely reserved numeric allocations.

## Incremental migration path

1. Decide whether the checked-in v1 format is already an immutable persisted or external contract.
2. Reconcile documentation with implemented unknown-event, `APPEND_EVENT`, Zstandard, and `FINAL` semantics.
3. Define session activity and terminal failure behavior, then cover root-exit-with-descendants and runtime-error
   transitions in tests.
4. Make journal position authoritative and stop rewriting metadata for every PTY output event. Add crash-point
   tests around journal append and metadata replacement.
5. If compatibility permits, simplify record framing before implementing retention or compression.
6. Consolidate the Rust toolchain pin.
7. Replace repeated termination timers and ambiguous connection draining with explicitly owned coordination.

Each step is independently reversible except a published binary-format change.

## Do not change

- Preserve raw PTY bytes and strictly increasing journal timestamps.
- Preserve payload-length checks before allocation and CRC validation before accepting records.
- Preserve input-UUID deduplication unless its delivery contract is deliberately replaced.
- Preserve fail-closed behavior when a requested sandbox is unavailable.
- Preserve Linux descendant identity checks and the explicitly documented macOS limitations. These protect real
  process-ownership invariants even though their implementation is substantial.

## Open questions

- Has protocol v1 already been persisted or consumed outside this module?
- Must metadata journal bounds be immediately current, or may they be a rebuildable snapshot?
- Does `failed` need to be a durable terminal state after every post-initialization error?
- How many simultaneous control clients must one session support?
- Is Zstandard required in v1, or was codec `1` intended only as a reserved allocation?
- Is `APPEND_EVENT` intended to be supported by this module now or only by a future implementation?

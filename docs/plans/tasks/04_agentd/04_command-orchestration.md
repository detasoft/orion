# Route Server Session Commands

Status: todo
Depends on: completed AgentD HTTP/2 transport, session discovery, and session
runtime/control; completed journal-reader (02e74a3a); 02_journal-sync.md;
completed native-control-contract (`c298ad34`); completed source-aware controls
(`09ed12c0`, `b3c8953c`);
[SERVER sequence recovery](../05_native-session-host/08_server-operation-sequence-recovery.md); and the
[native control-journal contract](03_session-host-contract-alignment/TASK.md)

- [ ] Route server session commands.
  - Owner: codex, session command-orchestration-d8e4, paused 2026-09-03 19:51 Europe/Amsterdam.
  - Next: Resume after journal sync, source-aware controls, and the canonical
    `SERVER` sequence recovery contract are integrated; then rebase and update
    the stale implementation plan to the resulting APIs.

Validate and route server commands while deriving durable outcomes exclusively
from each session journal.

## Scope

- Dispatch `START_SESSION`, `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` by
  command and session identity.
- Validate policy, lifecycle state, payload bounds, terminal dimensions, and
  runtime or workspace selection before local delivery.
- Preserve each exact server CBOR command envelope and assign one recovered,
  monotonic `SERVER` operation sequence across `INPUT`, `RESIZE`, `SIGNAL`, and
  `TERMINATE`; ignore `MANUAL` sequences during server recovery.
- Use bounded serial execution per session with cross-session concurrency;
  recover sequence and lifecycle observations by scanning the local journal to
  its tail before accepting commands.
- Complete commands from journaled host results and lifecycle records. Treat
  a missing result as unknown, `PROCESS_EXITED` as the only authoritative exit,
  and emit no separate `SESSION_STARTED` or successful direct `COMMAND_RESULT`.
- Represent a pre-journal start failure as a bounded in-memory failure-only
  session journal; persist no AgentD cursor or failure file.
- Test ordering, duplicates, recovery and crash windows, invalid or missing
  state, host reconnect, failure-only starts, and journal-authoritative exit.

---

## AgentD Command Orchestration Design

> Recovery update, 2026-09-10: the
> [SERVER sequence recovery contract](../05_native-session-host/08_server-operation-sequence-recovery.md)
> defines the atomic host claim, recorded lower bound, connection fence, and
> retention-watermark observation consumed below.

### Status

Approved on 2026-09-03. This design supersedes the command-result and
`SESSION_STARTED` portions of the broader 2026-09-02 AgentD plan. It does not
change the server-authoritative journal cursor or make AgentD a durable state
owner.

### Goals and Boundaries

The central server generates and durably persists every unique, single-use
`SessionId` and `CommandId`. Reusing either identifier for different work is a
protocol error. AgentD validates and routes commands but remains stateless: it
does not persist command results, operation counters, journal cursors, or start
failure files. `session-host` owns local execution and its durable journal. The
server owns durable command state and the authoritative journal replication
cursor.

Commands for one session execute through a bounded serial lane. Commands for
different sessions may execute concurrently. A busy, corrupt, or unreachable
session cannot block another session, control-stream liveness, or journal
replication.

`START_SESSION` is a session-creation operation keyed by the server-issued
session and command identities. The four established-session controls,
`INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE`, share one monotonically increasing
per-session `operationSequence` and one deduplication/ordering contract.

### Distinct Ordering Values

Three similarly named values have different owners and must not be converted
or substituted for one another:

- `eventId` is the unsigned, strictly increasing order of durable records in
  one session journal. The server replication cursor is an `eventId`.
- `operationSequence` is the order AgentD assigns to accepted local control
  attempts for one session. It appears in frame headers and command result
  records; it is not a journal cursor and gaps are valid.
- metadata `latestTimestamp` or similarly named latest-event fields are
  observational snapshots only. They are neither an `eventId` authority nor
  an `operationSequence` allocator.

`ACK_JOURNAL` carries a server-durably-committed journal `eventId` watermark to
the host through a source-aware `SERVER` operation with its own sequence and opaque envelope.
It produces `COMMAND_RESULT` when the result append succeeds. AgentD never
treats the host retention watermark as replication authority and never
persists it as an AgentD cursor.

### Considered Approaches

#### Minimal direct-result router

AgentD could serialize current v1 commands, return `COMMAND_RESULT` directly,
and retry only operations that look safe. This is small, but a lost result or
AgentD restart destroys the evidence needed to distinguish an accepted effect
from an unexecuted command. It cannot safely replay signals, ordered resizes,
or termination and does not satisfy restart recovery.

#### AgentD-persisted command ledger

AgentD could persist command IDs, counters, start fingerprints, and results
under its state directory. This recovers local results, but creates a second
durable command authority beside the server and a second execution authority
beside the host journal. It also adds crash consistency, retention, and
takeover coordination that the server-launched stateless model deliberately
avoids.

#### Journal-authoritative stateless router with a live-host claim

The selected approach observes command outcomes in the host journal and lets
the server complete commands only from replicated results. Local delivery and
admission are transient observations. The live host remains authoritative for
its accepted sequence high-water mark. A missing result leaves the effect
unknown, while an atomic claim fences older control connections and exposes the
admission value needed for safe allocation.

### Recovery and Sequence Allocation

After AgentD connects or restarts, the server supplies two durable facts for
each session:

1. the highest `operationSequence` covered by its durably acknowledged journal
   prefix; and
2. its authoritative committed journal `eventId` cursor.

AgentD independently scans the local journal strictly after that cursor through
the current tail. This scan is not an HTTP/2 upload and does not wait for the
replication pump. It finds recorded operation sequences, command results, and
lifecycle facts.

The unsigned maximum sequence in the server prefix and local suffix is only a
recorded lower bound. AgentD sends that floor through
`CLAIM_SERVER_CONTROL` after the scan reaches a stable tail. Under the same
lock used for operation admission, the live host rejects an inconsistent floor,
fences older control connections, and returns its accepted `SERVER` sequence
high-water mark. An older operation admitted first is included in the returned
value; one reaching admission after the claim is rejected without an effect.

AgentD enables the session lane only after a successful claim and initializes
its in-memory allocator to the unsigned successor of the host value. A host
value below the recorded floor, an unavailable host, an ambiguous claim, or
`u64::MAX - 1` pauses only that session. AgentD never raises host state from
journal evidence and never replays an envelope whose result is missing.

Commands awaiting recovery remain in the bounded session lane. Journal backlog
upload may continue independently. A retention gap or corrupt suffix pauses
only the affected session and is reported as an integrity failure. Correct
`ACK_JOURNAL` retention ensures deleted results are represented in the
server-durable prefix, but does not expose unrecorded admissions. The claim also
returns the host's durable retention watermark so journal sync can compare it
with the server event cursor without treating either event ID as an operation
sequence.

### Exact Command Envelope

AgentD validates a typed view of each server command while retaining the exact
CBOR item received from the server, including identifiers and future fields.
It must not reconstruct that envelope by re-encoding a Java record. The local
control request carries the allocated `operationSequence` and the unchanged
server envelope so the host can preserve it in the result record.

The host accepts a new sequence greater than its in-memory high-water mark;
gaps are valid. Any sequence at or below that mark is stale, including a
byte-identical retry. All established-session operation controls use this
contract, including `RESIZE`, `TERMINATE`, and `ACK_JOURNAL`.

### Established-Session Command Flow

For each command, AgentD performs bounded validation and enqueues the original
envelope in the session lane. When it reaches the head of the lane, AgentD
assigns the next `operationSequence` and sends both to the matching host.

The host then:

1. validates the operation sequence, envelope bounds, and typed effect;
2. advances its in-memory high-water mark and registers the admitted operation;
3. sends an empty `RECEIVED` admission receipt;
4. performs the requested side effect once; and
5. attempts a durable `COMMAND_RESULT` append with the same sequence, exact
   envelope, and succeeded or failed outcome.

Admission rejection returns `RECEIVED` with an error payload. Receipt delivery
failure does not cancel an admitted effect. Ordinary effects share a mutex;
`TERMINATE` bypasses it. Journal results are correlated by sequence, because
physical result order need not match admission order across connections.

AgentD may report transient delivery progress or failure on the control stream,
but sends no successful direct `COMMAND_RESULT`. The server completes a
command only after its journaled result is durably replicated.

A missing result leaves the effect unknown. A failed result can describe a
partial effect. Repeating a sequence is stale and does not return a saved
result; using a new sequence is a new attempt that may repeat a partial effect.
The host logs result-append failure and neither retries the effect nor
synthesizes a recovery result. AgentD must preserve this uncertainty across
reconnects.

### Session Start Flow

`START_SESSION` is not assigned an `operationSequence`. The server's unique,
single-use `SessionId` and `CommandId` identify the attempt. AgentD validates
runtime, workspace, policy, environment, command, terminal bounds, and session
collision before calling `SessionRuntime`.

If the host creates any journal, start success or failure comes only from that
journal. The server uses journaled start outcome records; there is no separate
`SESSION_STARTED` message and no successful direct `COMMAND_RESULT`.
`PROCESS_EXITED` is the sole authoritative process-completion record. Control
`STATUS`, endpoint reachability, PID observations, and termination acceptance
remain transient observations and never prove exit.

If launch fails before any host journal ever exists, AgentD constructs a
failure-only CBOR session journal in memory. It contains exactly one record,
with `eventId = 1` and type `SESSION_START_FAILED`, and sends it through the
normal session replication path. AgentD retains it only in bounded memory until
the server durably commits it, then discards it. A disconnect or AgentD crash
does not create a local cursor or failure file; the server may redeliver the
same start identity if it has not committed an outcome.

The diagnostic text is capped at 1 MiB. When larger, it retains the first
64 KiB and last 960 KiB and records the omitted byte count. Secret redaction is
explicitly deferred to
`docs/plans/tasks/04_agentd/05_diagnostic-secret-redaction.md`, which is
required before release. Until that task is complete, this diagnostic path is
not considered safe for production logging or transmission.

### Host Correlation and Deferred Incarnation Identity

An explicit `hostInstanceId` is deferred. The MVP correlates the server-issued
`SessionId`, the control endpoint located under that session directory, and the
manifest/status PID. PID equality is correlation rather than identity proof;
a stale socket plus PID reuse can route to the wrong host incarnation. The
implementation and operational documentation must state this risk and must not
claim cryptographic or durable incarnation fencing.

### Error Handling and Isolation

Protocol bounds and typed fields are validated before lane admission. Server
policy and lifecycle validation should prevent invalid work from reaching
AgentD; AgentD repeats safety-critical local validation before mutation. Host
admission failures are transient receipts with errors. Admitted side-effect
failures produce journaled command results when the result append succeeds.

Connection, timeout, queue-capacity, missing-session, corrupt-journal, and
unreachable-host reports on the control stream are transient delivery facts,
not durable command completion. The server keeps or resolves the durable
command according to journal evidence. AgentD does not automatically replay an
operation after ambiguous local delivery; it observes journaled results and
keeps a missing result unresolved.

Each session owns its bounded lane and recovery state. A lane failure rejects
or pauses only that session. Cross-session workers remain available, and
journal upload flow control cannot occupy the command or heartbeat capacity.
AgentD shutdown stops lane admission and boundedly drains or cancels AgentD
work without terminating any host or child process.

### Protocol and Task Migration

Current v1 contracts are insufficient and must evolve with compatibility
fixtures rather than silently changing frozen fields:

- Agent protocol decoding must expose the exact bytes of known command items
  and carry recovery's acknowledged operation sequence.
- Native control must use the header `operationSequence`, opaque command
  envelope, and typed effect for all source-aware `SERVER` operations. `RECEIVED` is an
  admission receipt; completion comes from the journal.
- Java journal projection must consume the native `COMMAND_RESULT` and
  `SESSION_START_FAILED` layouts and preserve the exact command envelope.
- Journal reading must expose operation and lifecycle observations while still
  preserving unknown records byte-for-byte.
- Server command handling must stop treating direct successful results as
  completion and instead project durable journal results.

Journal-sync sends a source-aware `SERVER` `ACK_JOURNAL` only from a complete server-durable
prefix and observes its journaled result. It must avoid a feedback loop driven
solely by ACK results. The focused Java native-control alignment was integrated
in `c298ad34`. Remaining Java/native conformance work is tracked by the
[alignment task](03_session-host-contract-alignment/TASK.md),
using the [current contract comparison](03_session-host-contract-alignment/TASK.md).

### Verification Design

Protocol and compatibility tests cover exact known-command CBOR preservation,
future tails, all new journal records, all four local-control envelopes,
sequence gaps, stale retry rejection, result correlation, and legacy fixture reading.

Command-orchestrator tests cover every command's validation and happy path;
same-session order; cross-session concurrency; bounded lane overload; unknown,
missing, corrupt, and journal-exited sessions; and independence from heartbeat
and upload backpressure.

Recovery tests provide different server prefix and local suffix maxima, append
while scanning, and large upload backlogs. Include an admitted operation whose
result is pending or missing; reaching the tail must not by itself enable a
sequence allocator. Verify the eventual allocation decision separately from
journal upload progress.

Failure-window tests cover disconnect around admission, during or after the
effect, during result append, and before server replication. They preserve
unknown outcomes and verify that stale rejection or missing records never
trigger automatic replay. Persisted results complete commands through normal
replication.

Start tests cover journaled success, journaled failure, collision/retry of the
same identities, failure before journal creation, the one-record in-memory
journal, `eventId = 1`, reconnect until durable server commit, the 1 MiB
first/last diagnostic bound and omission count, and absence of an AgentD cursor
or failure file. Secret cases belong to the separate redaction task.

Lifecycle tests prove `PROCESS_EXITED` alone transitions authoritative process
completion, while control status, socket loss, PID mismatch, and termination
acceptance do not. End-to-end tests combine host reconnect, journal replay,
server cursor recovery, `ACK_JOURNAL`, command result projection, and fair
multi-session progress.

---

## AgentD Command Orchestration Implementation Plan

> Contract update, 2026-09-07: native-control and recovery passages below are
> historical proposals, not a description of current runtime behavior. The
> [current native contract and Java interface comparison](03_session-host-contract-alignment/TASK.md)
> define source-aware in-memory admission, journaled command results, ACK, and
> start-outcome uncertainty.
> Recovery from recorded sequence maxima alone is not established when an
> admitted operation has a pending or missing result. Reconcile affected steps
> with that document before implementing the remaining orchestration work.

**Goal:** Route server session commands through stateless, bounded per-session orchestration whose durable
outcomes and recovery state come exclusively from session journals.

**Architecture:** Keep the exact inbound server CBOR item beside its typed message, queue commands in one
bounded serial lane per session, and run different session lanes concurrently. Observe recorded results through
the server-durable prefix and an independent local journal suffix scan. Resolve fresh sequence allocation
separately when admissions may have pending or missing results. The native host owns effect execution and
result append; journal-sync owns durable upload and source-aware `SERVER` ACK forwarding.

**Tech Stack:** Java 25, Maven, JUnit 5, AssertJ, Jetty HTTP/2, CBOR Sequence, existing AgentD runtime,
discovery, local-control, journal-reader, and journal-sync boundaries.

---

### Scope Rules

- Do not implement journal file reading, HTTP/2 journal pumping, `ACK_JOURNAL`, journal retention, or native
  host operation deduplication in this leaf.
- Do not add `hostInstanceId`; retain and document the approved SessionId/endpoint/PID correlation risk.
- Do not implement secret redaction here. The separate
  `docs/plans/tasks/04_agentd/05_diagnostic-secret-redaction.md` remains required before release.
- Do not implement server journal projection or command completion here. Deriving and sending the acknowledged
  operation prefix belongs to `docs/plans/tasks/03_agent-session-server/02_session-replication.md` with
  journal storage; pending-command completion belongs to
  `docs/plans/tasks/03_agent-session-server/03_command-service.md`.

#### Task 0: Satisfy and inspect prerequisite contracts

**Files:**
- Inspect: `docs/plans/tasks/agentd/journal-reader/TASK.md`
- Inspect: `docs/plans/tasks/04_agentd/02_journal-sync.md`
- Inspect: `docs/plans/tasks/native-session-host/control-journal-idempotency/TASK.md`
- Inspect: `agentd/src/main/java/pro/deta/orion/agentd/journal/`
- Inspect: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlCommand.java`
- Inspect: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlResult.java`
- Inspect: `session-host/protocol/README.md`
- Inspect: `session-host/protocol/fixtures/`

**Step 1: Rebase onto the prerequisite-complete main**

Run: `git status --short`

Expected: no output.

Run: `git rebase main`

Expected: the task branch rebases without a merge commit. Stop on any unrelated conflict; do not resolve by
discarding another task's work.

**Step 2: Verify the three prerequisite leaves are integrated**

Run: `test ! -e docs/plans/tasks/agentd/journal-reader/TASK.md`

Run: `test ! -e docs/plans/tasks/04_agentd/02_journal-sync.md`

Run: `test ! -e docs/plans/tasks/native-session-host/control-journal-idempotency/TASK.md`

Expected: all commands exit zero because completed dedicated-worktree leaves are removed during integration.
If any task remains, stop. Do not copy its implementation into this leaf.

**Step 3: Verify the native contract required by orchestration**

Confirm the integrated host contract and fixtures provide all of these:

```text
INPUT, RESIZE, SIGNAL, TERMINATE, ACK_JOURNAL use the source-aware `SERVER` operation wrapper
operationSequence is in the frame header; the payload retains the exact server CBOR envelope
admission advances an in-memory high-water mark and sends transient RECEIVED
an admitted effect executes once, then COMMAND_RESULT durable append is attempted
stale sequences are rejected, including identical retries
missing COMMAND_RESULT leaves the effect unknown and does not authorize replay
```

Expected: every line is implemented and covered by native fixtures/tests. Align Java controls with those
fixtures and a real host before enabling command routing.

Separately verify journal-sync sends a source-aware `SERVER` `ACK_JOURNAL` only for a complete server-durable prefix. Its
`RECEIVED` is admission only; observe effect completion through the journaled `COMMAND_RESULT`. Physical
cleanup is asynchronous. ACK scheduling must not form a feedback loop driven solely by ACK results.

**Step 4: Verify start-outcome coverage**

Inspect the native launch sequence and fixtures. Once a host journal exists, the host must eventually record
either the successful start observation (`PROCESS_STARTED`) or `SESSION_START_FAILED`, including failures after
journal creation but before child start.

Expected: no journal can be left permanently without a start outcome. This is currently an unowned prerequisite
gap; stop and ask the task coordinator to route it into the native-session-host task tree if it remains.

**Step 5: Record actual prerequisite API names before continuing**

The remaining steps use these expected names:

```text
SessionJournalReader.readAfter(...)
JournalReadResult.records()/tailEventId()/gap()
JournalSyncService.registerSyntheticJournal(...)
JournalSyncService.onSessionSync(...)
```

If prerequisite integration chose different names, update this plan's references in a documentation-only commit
before writing production code. Do not add forwarding interfaces solely to preserve guessed names.

#### Task 1: Preserve exact inbound Agent protocol items

**Files:**
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolItem.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolDecoder.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolDecoderTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/transport/AgentTransport.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/transport/JettyHttp2Transport.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/transport/JettyHttp2TransportTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/transport/JettyHttp2LivePeerTest.java`

**Step 1: Write the failing decoder ownership test**

Add a test that appends an unknown fourth field to a valid `INPUT`, decodes it, mutates the source array, and
asserts both the typed command and the exact original bytes remain available:

```java
SequenceDecodeResult<AgentProtocolItem> result = decoder.accept(ByteBuffer.wrap(source));
AgentProtocolItem item = decoded(result).getFirst();
assertThat(item.message()).isInstanceOf(AgentMessage.Input.class);
assertThat(item.encoded().toByteArray()).containsExactly(expectedWithFutureTail);
```

Keep the existing chunk-boundary, valid-prefix, semantic-rejection, terminal-failure, and reset assertions.

**Step 2: Run the decoder test to verify it fails**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolDecoderTest'`

Expected: FAIL because `AgentProtocolItem` does not exist and the decoder returns only `AgentMessage`.

**Step 3: Add the raw-item value and make decoding own the bytes**

Implement the public immutable value:

```java
public record AgentProtocolItem(AgentMessage message, ProtocolBytes encoded) {
    public AgentProtocolItem {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(encoded, "encoded");
    }
}
```

Change `AgentProtocolDecoder` to decode each complete item into `AgentProtocolItem`, copying the exact source
slice through `ProtocolBytes.copyOf(bytes, from, to)`. Do not re-encode `message`.

**Step 4: Run the decoder test to verify it passes**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolDecoderTest'`

Expected: PASS with exact bytes preserved across arbitrary chunks and source mutation.

**Step 5: Write the failing transport delivery tests**

Update transport tests so the control callback receives `AgentProtocolItem` and sees the exact encoded command,
while the session callback still receives its typed `AgentMessage`. Add a live-peer assertion with a known
command carrying a future tail.

**Step 6: Run the transport tests to verify they fail**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.transport.JettyHttp2TransportTest,pro.deta.orion.agentd.transport.JettyHttp2LivePeerTest'
```

Expected: FAIL because `AgentTransport.onControlMessage` still exposes only `AgentMessage`.

**Step 7: Deliver raw control items without changing session-stream semantics**

Change only the control callback to `Consumer<AgentProtocolItem>`. In `JettyHttp2Transport`, pass the complete
item to control receivers and unwrap `item.message()` for existing session-stream receivers. Retain the current
single callback executor, stream-generation fencing, and recoverable/terminal decode behavior.

**Step 8: Run focused protocol and transport tests**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolDecoderTest'`

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.transport.JettyHttp2TransportTest,pro.deta.orion.agentd.transport.JettyHttp2LivePeerTest'
```

Expected: PASS.

#### Task 2: Add recovery sequence and Java journal-event contracts

**Files:**
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/OperationSequence.java`
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionCommandOutcome.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentMessage.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolCodec.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventType.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventPayload.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolCodecTest.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolFixtureTest.java`

**Step 1: Write failing unsigned operation-sequence tests**

Mirror the verified unsigned ordering behavior of `EventId`:

```java
OperationSequence low = new OperationSequence(1);
OperationSequence high = OperationSequence.fromUnsigned(BigInteger.ONE.shiftLeft(63));
assertThat(low.compareTo(high)).isNegative();
assertThat(high.toString()).isEqualTo("9223372036854775808");
```

Add `SESSION_SYNC` codec cases with an absent and present appended acknowledged sequence. Legacy three-field
`SESSION_SYNC` must remain readable.

**Step 2: Run the control codec test to verify it fails**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolCodecTest'`

Expected: FAIL because `OperationSequence` and the `SESSION_SYNC` tail do not exist.

**Step 3: Implement the strong value and `SESSION_SYNC` tail**

Implement `OperationSequence` with the same unsigned-64 conversion and comparison shape as `EventId`. Extend
`AgentMessage.SessionSync` with `Optional<OperationSequence> acknowledgedOperationSequence`; encode it as the
next appended field and accept the legacy absence. Keep `afterEventId` and the operation prefix distinct.
This leaf owns the shared Java wire contract and compatibility tests. The server replication task owns deriving
the value from its committed journal projection and populating outbound `SESSION_SYNC`; do not implement that
server behavior here.

**Step 4: Run the control codec test to verify it passes**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolCodecTest'`

Expected: PASS, including old message readability.

**Step 5: Write failing journal-event and fixture tests**

Use the numeric event allocations and payload order already frozen by the integrated native fixture. Add typed
round trips for:

```java
new SessionEventPayload.ProcessStarted(processId)
new SessionEventPayload.CommandResult(source, operationSequence, sourceEnvelope, outcome, detail)
new SessionEventPayload.SessionStartFailed(commandId, diagnostic, omittedByteCount)
```

`SessionCommandOutcome` contains `SUCCEEDED`, `FAILED`, `REJECTED`, and `AMBIGUOUS`; do not reuse the direct
control-message `CommandOutcome.DUPLICATE` value. Verify unknown records remain byte-for-byte opaque.

**Step 6: Run event tests to verify they fail**

Run:

```bash
make run-test MODULE=agent-protocol \
  TEST='pro.deta.orion.agent.protocol.SessionEventCodecTest,pro.deta.orion.agent.protocol.AgentProtocolFixtureTest'
```

Expected: FAIL because Java does not know the prerequisite host event allocations or payloads.

**Step 7: Implement only the Java-side event model and codec**

Add the typed payload records, bounds, and codec cases. `COMMAND_RESULT` must preserve its source, operation
sequence, and exact source envelope for server/local projection. Extract server command identity from a
preserved `SERVER` envelope in the consuming projection; never interpret a `MANUAL` envelope as a server
command.
`SESSION_START_FAILED` must carry the omission count separately from diagnostic text. Do not change the native
writer in this leaf.

**Step 8: Run all Agent protocol tests**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.*Test'`

Expected: PASS with legacy fixtures readable and the new shared native fixtures byte-identical.

#### Task 3: Reconstruct command state from the local journal suffix

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/CommandJournalScanner.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandState.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/CommandJournalScannerTest.java`

**Step 1: Write the failing recorded-state recovery tests**

Build prerequisite-reader records with result sequences `7` and `11` and server prefix `9`. Assert the scan
observes maximum recorded sequence `11` and reaches the local tail. Repeat with an empty suffix and absent
prefix. These are recorded-history facts, not proof of the host's complete admission high-water mark.

Add an admitted operation whose result is pending or missing. The scanner must leave sequence allocation
unresolved rather than treating recorded maximum plus one as safe. Only the prerequisite atomic host claim may
turn this recorded lower bound into an allocation-ready state.

**Step 2: Run the scanner test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandJournalScannerTest'`

Expected: FAIL because the scanner and state do not exist.

**Step 3: Implement the minimal independent suffix scan**

Inject the prerequisite `SessionJournalReader`; call `readAfter` with the server `eventId` cursor and iterate
with ordinary loops. Observe known command results and lifecycle payloads while leaving every record available
to journal sync. Return the recorded maximum `SERVER` operation sequence and tail observation without claiming
they expose admissions whose results are pending or missing. Exclude `MANUAL` sequences from server recovery
and pass the unsigned maximum of this value and the server-prefix value to the atomic claim.

**Step 4: Add failing lifecycle and missing-result tests**

Cover `PROCESS_EXITED`, persisted command results, missing results after uncertain delivery, and duplicate
observations. Preserve unknown outcomes without synthesizing a host result or replaying an effect. Metadata
state and control `STATUS` never set authoritative exit.

**Step 5: Run the scanner test to verify the new cases fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandJournalScannerTest'`

Expected: FAIL until lifecycle and command-result observations are represented.

**Step 6: Complete immutable recovered state**

`SessionCommandState` should contain the recorded sequence maximum, journal-authoritative exit flag, observed
command results, scanned tail `eventId`, claimed host sequence high-water mark, host retention watermark, and
allocation readiness. A successful claim initializes the allocator to the host value's unsigned successor;
inconsistent or exhausted values keep the state blocked. Do not copy metadata `latestTimestamp` into any of
these fields.

**Step 7: Add concurrent-tail handoff and failure tests**

Use a fake reader that appends between pages. Prove the scanner reaches a stable tail or subscribes through the
prerequisite reader's handoff without missing the append. Cover retention gap and corrupt suffix as per-session
blocked results rather than exceptions that stop AgentD.

**Step 8: Run the scanner tests to verify they pass**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandJournalScannerTest'`

Expected: PASS.

#### Task 4: Add bounded per-session serial lanes

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/ServerSessionCommand.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandScheduler.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionCommandSchedulerTest.java`

**Step 1: Write the failing same-session order test**

Represent a queued command as its typed `AgentMessage`, exact `ProtocolBytes`, and SessionId. Block the first
handler invocation, enqueue three commands, then release it:

```java
assertThat(observed).containsExactly("session-a/one", "session-a/two", "session-a/three");
```

**Step 2: Run the scheduler test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandSchedulerTest'`

Expected: FAIL because the scheduler does not exist.

**Step 3: Implement one drain task per active session lane**

Use a `ConcurrentHashMap<SessionId, Lane>` and a shared executor. Each `Lane` owns an `ArrayDeque`, capacity,
recovery-ready flag, and one `draining` bit. Enqueue under the lane lock; execute handlers outside it. Do not use
a global command lock and do not create a thread solely to enforce each I/O timeout.

**Step 4: Add failing cross-session and recovery-gate tests**

Block session A and prove session B completes. Enqueue before `markRecovered` and prove nothing executes until
the scanner result and successful host claim are installed, then assert the queued commands drain in order.

**Step 5: Run the scheduler test to verify the new cases fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandSchedulerTest'`

Expected: FAIL until lanes are independent and recovery-gated.

**Step 6: Add capacity, lane failure, and close behavior**

Return typed `Accepted`, `Full`, and `Closed` admission results. A handler failure affects only that lane and must
not strand its later work. `close()` stops admission, cancels/drains bounded AgentD work, joins owned workers,
and never sends termination to a host.

**Step 7: Run the scheduler test to verify it passes**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandSchedulerTest'`

Expected: PASS for FIFO, cross-session concurrency, capacity, recovery gating, isolated failure, and close.

#### Task 5: Route established-session controls through the host contract

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandDispatcher.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/TransientCommandReport.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlCommand.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlResult.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionControlClient.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionCommandDispatcherTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionControlClientTest.java`

**Step 1: Write failing happy-path mapping tests for all four controls**

For `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE`, assert the dispatcher sends the same allocated sequence and
exact server envelope to the prerequisite native control client:

```java
assertThat(sent.operationSequence()).isEqualTo(new OperationSequence(12));
assertThat(sent.commandEnvelope()).isEqualTo(serverItem.encoded());
assertThat(sent.command()).isEqualTo(serverItem.message());
```

**Step 2: Run dispatcher tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandDispatcherTest'`

Expected: FAIL because the dispatcher does not exist and current commands lack the shared envelope/sequence.

**Step 3: Adapt the Java control client to the integrated native contract**

Remove automatic operation retries. Encode the operation sequence in the frame header and preserve the exact
opaque envelope in the source-aware `SERVER` payload. Decode empty or rejected `RECEIVED` as transient admission, and
observe completion through `COMMAND_RESULT`. Remove the native timestamp/duplicate-response model.
Continue using `OperationDeadline` and transport-native cancellation; do not add per-call timeout threads.

**Step 4: Run local control tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.session.NativeControlCodecTest,pro.deta.orion.agentd.session.SessionControlClientTest'
```

Expected: PASS for all four operations, transient admission, stale rejection, journaled result correlation,
framing, timeout, and uncertain delivery without automatic replay.

**Step 5: Implement routing and transient reports**

Look up the `LocalSession` by server SessionId, reject a journal-authoritative exited state before delivery,
and route through its manifest endpoint. A receipt does not prove execution or durable completion. Observe
results through the journal and emit direct control reports only for rejected admission or transient delivery
failures; never emit `CommandOutcome.SUCCEEDED`.

**Step 6: Add failure and duplicate tests**

Cover unknown session, degraded/corrupt recovery, exited journal, host rejection, connection failure, timeout,
stale rejection after same-envelope retry or conflicting reuse, missing results, and receipt loss around
execution. A stale rejection must not resolve an earlier uncertain attempt.

**Step 7: Run dispatcher and control tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.session.SessionCommandDispatcherTest,pro.deta.orion.agentd.session.SessionControlClientTest'
```

Expected: PASS; no test observes a direct successful `COMMAND_RESULT`.

#### Task 6: Route START and create failure-only journals

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/StartSessionHandler.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/LaunchDiagnostic.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/SyntheticSessionJournal.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/StartSessionHandlerTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/journal/SyntheticSessionJournalTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionSpec.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/SessionContractsTest.java`

**Step 1: Write failing START mapping tests**

Cover native runtime selection, existing-directory workspace, environment, terminal bounds, sandbox policy,
and unknown runtime/workspace. Preserve the server SessionId and CommandId; do not allocate operation sequence.

**Step 2: Run START tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.StartSessionHandlerTest'`

Expected: FAIL because the handler and complete mapping do not exist.

**Step 3: Implement validation and runtime dispatch**

Convert `AgentMessage.StartSession` into the existing `SessionSpec`/`WorkspaceReference` model with explicit
typed rejection for unsupported runtime, managed workspace, environment, or policy. On launch completion,
probe through the prerequisite journal reader rather than inferring start outcome from `SessionLaunchResult` or
control `STATUS`.

**Step 4: Add failing journal-present outcome tests**

Test both `PROCESS_STARTED` and journaled `SESSION_START_FAILED`. Also test a host-created empty journal: the
handler must not synthesize a competing failure; it reports the contract violation and leaves server completion
pending for journal reconciliation.

**Step 5: Run START tests to verify the new cases fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.StartSessionHandlerTest'`

Expected: FAIL until journal outcome routing replaces direct success.

**Step 6: Write failing diagnostic-boundary tests**

Use ASCII bytes at the boundary so byte counts are exact:

```java
assertThat(bound.detailBytes()).hasSize(1024 * 1024);
assertThat(bound.detailBytes()).startsWith(first64KiB);
assertThat(bound.detailBytes()).endsWith(last960KiB);
assertThat(bound.omittedByteCount()).isEqualTo(original.length - 1024 * 1024L);
```

Cover below limit, exact limit, one byte over, and a multi-megabyte input. This class truncates only; it must not
claim redaction. Do not add secret-pattern tests here.

**Step 7: Implement the bounded in-memory failure journal**

When and only when no journal exists, encode one `SESSION_START_FAILED` record with `eventId = 1`, CommandId,
bounded diagnostic, and omission count. `SyntheticSessionJournal` exposes immutable record bytes to the
prerequisite journal-sync registration seam and releases them after durable server acknowledgement. It writes no
file and stores no cursor.

**Step 8: Add reconnect and no-file tests**

Fail the first synthetic upload, retry within the same process, acknowledge the second, and assert identical
record bytes. Verify the session directory contains no AgentD failure or cursor file. Model AgentD restart by
discarding the object and redelivering the same START identity, not by loading local state.

**Step 9: Run START and synthetic-journal tests**

Run:

```bash
agentd_start_tests='pro.deta.orion.agentd.session.StartSessionHandlerTest,'\
'pro.deta.orion.agentd.journal.SyntheticSessionJournalTest,'\
'pro.deta.orion.agentd.runtime.SessionContractsTest'
make run-test MODULE=agentd TEST="$agentd_start_tests"
```

Expected: PASS for journaled outcomes, failure-only record, exact bound, reconnect, and no local persistence.

#### Task 7: Observe live journal lifecycle and command results

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionJournalObserver.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionJournalObserverTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandScheduler.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionCommandSchedulerTest.java`

**Step 1: Write the failing observation tests**

Feed `COMMAND_RESULT`, `PROCESS_STARTED`, and `PROCESS_EXITED` records in event order. Correlate results by
operation sequence, including results whose physical journal order differs from operation admission order.
Assert duplicate observations are harmless, result observations wake replication, and only `PROCESS_EXITED`
marks process completion.

**Step 2: Run observer tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionJournalObserverTest'`

Expected: FAIL because the observer does not exist.

**Step 3: Implement one observation path for recovery and live tailing**

Use the same `observe(SessionEventRecord)` method from `CommandJournalScanner` and the prerequisite live-tail
callback. Track event order and operation facts in the lane state. Do not create a second journal reader or
advance the HTTP/2 replication cursor.

**Step 4: Add the queued-command/exit race test**

Queue a command behind a blocked operation, publish `PROCESS_EXITED`, then unblock. Assert the queued command is
not delivered locally and gets a transient non-completion report. Feed status-exited without the journal event
and assert it does not make the same transition.

**Step 5: Run observer and scheduler tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.session.SessionJournalObserverTest,pro.deta.orion.agentd.session.SessionCommandSchedulerTest'
```

Expected: PASS.

#### Task 8: Dispatch post-handshake server commands

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentControlHandler.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/CommandOrchestrator.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/CommandOrchestratorTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentControlService.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlServiceTest.java`

**Step 1: Write the failing post-WELCOME dispatch test**

Have the fake transport send `WELCOME`, then an exact `INPUT` item. Assert the handler receives only the second
item and retains its raw bytes. Also assert a command before `WELCOME` still fails negotiation.

**Step 2: Run the control-service test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.core.AgentControlServiceTest'`

Expected: FAIL because `AgentControlService.receiveControl` ignores every message after negotiation.

**Step 3: Add handshake-gated delegation**

Inject `AgentControlHandler`. Before negotiation it accepts only `WELCOME`; afterward it delegates the complete
`AgentProtocolItem`. Handler failure must be isolated/logged without throwing on the Jetty callback executor.
Keep credential clearing and handshake timeout behavior unchanged.

**Step 4: Write failing orchestrator routing tests**

Cover all five command types, `SESSION_SYNC` recovery input, unknown server-direction messages, queue full,
closed orchestrator, and per-session initialization. Assert the orchestrator delegates sync cursor/prefix to
journal sync plus recovery scanning and never parses metadata timestamps as cursors.

**Step 5: Run orchestrator tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandOrchestratorTest'`

Expected: FAIL because `CommandOrchestrator` does not exist.

**Step 6: Implement orchestration coordination**

Route START to `StartSessionHandler`; route the four controls through the scheduler and dispatcher. On
`SESSION_SYNC`, start journal upload through the prerequisite service and independently scan the local suffix.
After the scan reaches its stable tail, claim the live host with the recorded floor and enable the lane from the
returned high-water mark. Upload may remain backlogged, but scan completion without a claim never enables
delivery.

**Step 7: Add backlog independence and isolation tests**

Hold the journal upload future open, finish the local scan, and assert a command executes. Hold one session scan
or command and assert another session plus a heartbeat/control send remains available.

**Step 8: Run control and orchestrator tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.core.AgentControlServiceTest,pro.deta.orion.agentd.session.CommandOrchestratorTest'
```

Expected: PASS.

#### Task 9: Assemble lifecycle, shutdown, and recovery services

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/Agent.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentConfiguration.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentAssemblyTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentConfigurationTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentLifecycleTest.java`

**Step 1: Write the failing assembly-order test**

Assert process lock starts before local discovery/recovery and transport connection; command handlers are
registered before connect; and a discovered session begins recovery only after the server supplies
`SESSION_SYNC` cursor and acknowledged operation sequence. The lane remains blocked until the suffix scan and
atomic host claim both complete.

**Step 2: Run assembly tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.core.AgentAssemblyTest'`

Expected: FAIL because `Agent.create` currently assembles only the process lock and handshake service.

**Step 3: Add bounded orchestration configuration**

Add explicit positive defaults for per-session queue capacity, recovery concurrency, initialization timeout,
and local-control timeout. Keep them internal/defaulted unless an existing configuration source already owns
the values; do not add speculative CLI options.

**Step 4: Assemble existing prerequisite and task-owned services**

Construct discovery/registry, native runtime, prerequisite journal reader/sync, scanner/observer, scheduler,
dispatcher/start handler, command orchestrator, and control service. Choose lifecycle order so callbacks cannot
reach an unstarted service and reverse close cannot terminate hosts.

**Step 5: Add failing shutdown/isolation tests**

Close with queued and active work, a blocked journal upload, and multiple sessions. Assert AgentD-owned workers
stop, outbound sends fail boundedly, and no local `TERMINATE` is sent. Assert one recovery failure does not close
the transport or another lane.

**Step 6: Run assembly, configuration, and lifecycle tests**

Run:

```bash
agentd_assembly_tests='pro.deta.orion.agentd.core.AgentAssemblyTest,'\
'pro.deta.orion.agentd.core.AgentConfigurationTest,'\
'pro.deta.orion.agentd.core.AgentLifecycleTest'
make run-test MODULE=agentd TEST="$agentd_assembly_tests"
```

Expected: PASS.

#### Task 10: Verify the end-to-end AgentD command flow

**Files:**
- Create: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentCommandLivePeerTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlLivePeerTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`

**Step 1: Write a failing live control-stream command test**

Extend the existing TLS HTTP/2 peer pattern: send `WELCOME`, a `SESSION_SYNC` with prefix/cursor, then commands.
Use fake prerequisite journal/control boundaries so the test observes exact envelope bytes, assigned sequence,
and no direct success result.

**Step 2: Run the live-peer test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.core.AgentCommandLivePeerTest'`

Expected: FAIL because the end-to-end command assembly is not wired.

**Step 3: Complete only missing wiring exposed by the test**

Keep the live peer bounded and deterministic. Do not add retry loops to the test or production code to hide
ordering races.

**Step 4: Add crash-window and start-failure scenarios**

Cover:

```text
uncertain delivery without COMMAND_RESULT -> unknown outcome, no automatic replay
receipt loss with a pending or missing result -> host claim advances above the admission without replay
persisted COMMAND_RESULT before server replication -> normal journal recovery completes the command
large upload backlog -> local scan proceeds independently; allocation readiness is checked separately
older buffered control connection -> fenced after claim and cannot race the recovered allocator
PROCESS_EXITED -> later command is not delivered
pre-journal START failure -> one in-memory eventId=1 failure record
```

**Step 5: Run live-peer, runtime, and session tests**

Run:

```bash
agentd_flow_tests='pro.deta.orion.agentd.core.AgentCommandLivePeerTest,'\
'pro.deta.orion.agentd.core.AgentControlLivePeerTest,'\
'pro.deta.orion.agentd.runtime.NativeRuntimeTest,pro.deta.orion.agentd.session.*Test'
make run-test MODULE=agentd TEST="$agentd_flow_tests"
```

Expected: PASS.

#### Task 11: Align protocol and architecture documentation

**Files:**
- Modify: `agent-protocol/protocol/README.md`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/package-info.java`

**Step 1: Update the protocol reference**

Document the appended `SESSION_SYNC` acknowledged operation prefix, exact raw command-envelope preservation,
new journal event payloads, and the distinction among `eventId`, `operationSequence`, and metadata
`latestTimestamp`. Preserve the v1 compatibility section and fixture names.

**Step 2: Replace obsolete local-control comments**

Update the session package comment that currently says no general operation sequence and INPUT-only retry.
State all-four sequencing, journal-result authority, and the intentionally deferred host-incarnation proof.

**Step 3: Align the broad AgentD plan**

Replace only the superseded claims that say there is no acknowledgement protocol, require separate
`SESSION_STARTED`, or treat a direct successful `COMMAND_RESULT` as durable completion. Link to the approved
design for detailed recovery and crash semantics. Also replace the INPUT-only retry/no-general-sequence claim
and timestamp-named cursor language with the all-four sequence contract and `eventId` terminology. Do not
rewrite unrelated AgentD sections.

**Step 4: Run documentation checks**

Run: `git diff --check`

Expected: no output and exit zero.

#### Task 12: Final verification and review preparation

**Files:**
- Inspect: all files changed from the rebased task base
- Inspect: `docs/reviews/RULES.md`
- Inspect: every changed class-level `@AiRule` comment

**Step 1: Review the complete branch diff**

Run: `git status --short`

Expected: no output.

Run: `git diff --check main...HEAD`

Expected: no output.

Run: `git diff --stat main...HEAD`

Expected: only Agent protocol, AgentD, compatibility-fixture, and scoped documentation changes described above;
no native host implementation, server implementation, or redaction implementation.

**Step 2: Run focused regression groups once more**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.*Test'`

Run:

```bash
agentd_regression_tests='pro.deta.orion.agentd.session.*Test,'\
'pro.deta.orion.agentd.core.AgentCommandLivePeerTest,'\
'pro.deta.orion.agentd.core.AgentAssemblyTest'
make run-test MODULE=agentd TEST="$agentd_regression_tests"
```

Expected: PASS.

**Step 3: Run routine development verification**

Run: `mvn verify -Pdev -T 4`

Expected: `BUILD SUCCESS`.

**Step 4: Run the required full project tests**

Run: `make test`

Expected: PASS.

**Step 5: Confirm deferred and delegated work stayed out of scope**

Verify:

```text
no hostInstanceId was added
no AgentD cursor or failure file was added
no native reader/sync/ACK implementation was copied into this leaf
no server command projection was implemented
no secret redaction implementation was added
no direct successful COMMAND_RESULT or separate SESSION_STARTED was added
```

Do not add tests whose only purpose is proving removed legacy behavior is absent; establish these from the
positive contract tests and the reviewed diff.

**Step 6: Hand off for review**

Record the host-incarnation risk and the still-required diagnostic-redaction/server-projection follow-ups.

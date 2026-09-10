# Align AgentD with the Stabilized Session-Host Contract

Status: todo (runtime alignment remains; documentation audit completed 2026-09-07)
Parent: ../TASK.md
Session-host review: ../../../../../../../../session-host/MODULE_REVIEW.md

Bring every remaining AgentD session-host integration path to the final native
control, journal, metadata, and lifecycle contracts after the focused control
model changes are complete. The documentation audit accepts the current native
implementation as the baseline; it did not change Java or Rust runtime behavior.
The linked contract records the current Java/native interface differences.

## Scope

- Audit AgentD launch, discovery, control transport, journal projection,
  recovery, retention acknowledgement, and status handling against the current
  session-host protocol and compatibility fixtures.
- Treat `RECEIVED` as transient admission only. Observe operation completion
  through `COMMAND_RESULT` and preserve the documented unknown/partial-effect
  semantics after ambiguous delivery or a missing result record.
- Use the single schema-2 operation sequence and exact opaque command envelope
  without restoring a duplicate native command identity, result ledger, or
  schema-1 operation fallback.
- Put the operation sequence in the frame header; encode only envelope length,
  envelope, and effect in the payload. Replace the Java timestamp/duplicate ACK
  model with empty or rejected `RECEIVED` and correlate results by sequence.
- Preserve uncertain delivery across reconnects: a stale rejection cannot
  resolve an earlier attempt. A journal suffix does not expose admissions with
  pending or missing results, so recorded maxima alone do not prove a fresh
  sequence. Resolve allocation with command orchestration before completion.
- Send `ACK_JOURNAL` through the same schema-2 operation contract and advance
  it only from a server-durable journal prefix.
- Consume the four-byte `TERMINATE` effect and effective sandbox status without
  adding AgentD-owned process-tree or host-shutdown policy.
- Preserve current start-outcome and journal-failure behavior: output append
  failures can discard chunks while the child continues; failed
  `PROCESS_STARTED` persistence can leave a live host without a start record.
  Neither condition implies a native shutdown or a recoverable journal gap.
- Remove superseded Java branches, adapters, aliases, retry assumptions, and
  legacy-only tests once every real consumer uses the canonical path. Remove
  legacy-only negative coverage in a separate commit as required by AGENTS.md.

## Acceptance

- AgentD can launch and rediscover a real session host, issue every established
  operation, reconnect after an uncertain control exchange, and distinguish
  admission from the durable result observed in the journal.
- Journal resume and `ACK_JOURNAL` use one server-confirmed durable prefix and
  preserve retention-gap reporting.
- Start success, pre-exec start failure, missing operation result, partial
  effect, graceful terminate, force terminate, and sandbox status match the
  current session-host contract, including continued service after journal
  append failure and no fabricated start outcome after exec.
- Shared protocol fixtures and real-host integration tests cover unsigned
  sequences above `i64::MAX`, unknown envelope fields, reconnect, and ACK.
- No obsolete schema-1 operation path, duplicate command identity, private
  durable command cursor, or AgentD-owned host termination coordinator remains.

## Boundary

This task owns AgentD conformance with the established native host contracts
and removal of superseded AgentD paths. It does not change the session-host wire
or journal formats, implement server command orchestration, or add local terminal
UI behavior.

---

## Native Control and Journal Contract

Status: reconciled with the current implementation on 2026-09-07.
This replaces the 2026-09-03 intent/result-ledger design. Current native
behavior is the accepted baseline; this document does not request runtime
changes. Wire layouts are specified in
[the native protocol](../../../../../session-host/protocol/README.md). Implementation
references are [native framing](../../../../../session-host/src/protocol.rs),
[Unix admission and execution](../../../../../session-host/src/platform/unix.rs), and
[retention publication](../../../../../session-host/src/journal_acknowledgement.rs).

### Owners and implementation boundary

`session-host` owns the process tree, PTY, local journal, control admission,
and retention permission. The server owns durable replicated history,
termination timing, and escalation. AgentD launches and discovers hosts and
provides the local Java control client. Server command orchestration and
journal synchronization remain queued work, not completed integration.

The Rust host and Java client currently expose different operation layouts.
The comparison below records both implementations; the
[alignment task](TASK.md)
tracks the remaining Java integration work. Do not infer interoperability from
both sides calling their payload schema `2`.

### Native operation contract

`INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL` use schema 2.
One unsigned sequence in the 32-byte frame header identifies the operation and
correlates its response. Live operation admission accepts values from `1`
through `u64::MAX - 1`; gaps and values above `i64::MAX` are valid.
`u64::MAX` is reserved for responses without an associated sequence.

The little-endian operation payload is:

```text
u32 commandEnvelopeLength
commandEnvelopeLength opaque bytes
command-specific effect bytes
```

The nonempty envelope is preserved exactly without decoding its CBOR contents.
There is no separately encoded native CommandId or payload operation sequence.
`INPUT` retains its 16-byte input UUID and raw bytes; `RESIZE` contains two u32
dimensions; `SIGNAL` contains u16 kind, u16 reserved zero, and i32 platform code;
`TERMINATE` contains only u16 mode and u16 reserved zero; `ACK_JOURNAL` contains
one u64 journal event ID. The complete payload is bounded to 16 MiB.

`STATUS` remains a schema-1 empty request with a 64-byte response. The
`APPEND_EVENT` schema-1 layout is reserved, but the live Unix host rejects it
because ordered harness ingress is not implemented.

### Admission, execution, and journal results

The host keeps only an in-memory accepted-sequence high-water mark for replay
protection. A sequence at or below it is rejected, including a byte-identical
retry. Admission advances the mark and registers an active operation; it does
not append a durable intent or retain a result ledger.

The host sends an empty `RECEIVED` before applying the effect. Admission
rejection uses `RECEIVED` with u32 error code and bounded UTF-8 detail.
Failure to deliver the receipt is logged and does not cancel the admitted
effect. `ERROR` handles other control errors. Response frames use schema 1.

After executing an admitted effect once, the host attempts to durably append:

```text
[eventId, COMMAND_RESULT,
 [operationSequence, exactCommandEnvelope, outcome, detail]]
```

A live host produces succeeded or failed results. Rejected and ambiguous
outcomes remain values understood by shared journal readers. Admission
rejection does not create a result record. A failed effect can have partial
side effects; `PTY_INPUT` records requested bytes, not confirmed delivery.
A result-append failure is logged to stderr and does not replay the effect.
A missing result therefore means unknown outcome.

Ordinary effects share a mutex; `TERMINATE` bypasses it to signal descendants
while an ordinary effect is blocked. Sequences identify attempts, not FIFO
positions across connections. Match journal results by sequence rather than
record position. The host has no grace timer, escalation loop, or signal retry.

### Journal acknowledgement and retention

`ACK_JOURNAL` uses the same admission and result path as the other operations,
including an opaque envelope and its own operation sequence. Its watermark
must represent a complete server-durable prefix; a network write alone is
not authority to delete local history. Java forwarding is still pending.

During effect execution, the host rejects zero watermarks and values beyond
the current journal tail. These effect failures produce failed results when
the journal is writable. A greater valid watermark is published in
`control-retention-state`, containing `stateVersion: 1` and
`acknowledgedEventId`. Publication writes and syncs a temporary file, renames
it, and syncs the directory before newly covered deletion is authorized.
The sidecar is local deletion permission, not an AgentD replication cursor.

Repeated or lower watermarks in newly admitted operations do not lower the
stored watermark. Reusing an operation sequence is still stale. The handler
requests retention maintenance and then follows the ordinary `COMMAND_RESULT`
path. `RECEIVED` does not confirm checkpoint publication or physical deletion.
An ACK itself adds a result record; it does not acknowledge that new record.
ACK scheduling, including avoiding a feedback loop driven solely by ACK
results, belongs to the pending journal-sync implementation.

Compression is independent of acknowledgement. Physical-size retention can
delete only the oldest closed prefix covered by the durable watermark; it
never deletes the active segment or unacknowledged history to meet the size
target. Compression/deletion failures do not revoke a durable watermark.
Readers behind the retained floor report a retention gap.

### Start and journal-write failures

After journal creation, the host attempts `PROCESS_STARTED` after the child
crosses exec, or `SESSION_START_FAILED` on an earlier start failure. A durable
start outcome exists only when its append succeeds. Failure to append
`PROCESS_STARTED` is logged; the host still publishes the live session.
It does not substitute a false pre-exec failure or terminate the child solely
because this append failed.

The PTY reader continues draining after an output append fails. Failed chunks
are discarded, later chunks may be journaled, and the child remains running.
There is no durable gap event or STATUS degradation flag for this loss.
A reader cannot prove complete terminal history from increasing event IDs.
These are accepted current behaviors, not requests for fatal journal handling.

Metadata remains a discovery manifest, not a lifecycle record or journal
index. Live STATUS reports current process observations and journal bounds;
missing journal evidence cannot be reconstructed from metadata.

### Current Java interface and remaining alignment

Sources: `ControlCommand`, `NativeControlCodec`, `ControlResult`, and
`SessionControlClient` under `agentd/src/main/java/pro/deta/orion/agentd/session/`.

| Area | Current AgentD implementation | Current native host |
| --- | --- | --- |
| Frame correlation | Independent positive request ID allocated by the client | Operation sequence in the header |
| Schema-2 prefix | u64 operation sequence, u16 CommandId length, CommandId, u32 envelope length, envelope | u32 envelope length and envelope |
| TERMINATE effect | Eight bytes, including u32 graceMillis | Four bytes, mode and reserved zero |
| Operation response | 0x8000/0x8001 select accepted/duplicate, with an eight-byte journal timestamp | 0x8000 RECEIVED, empty or an error payload |
| ACK_JOURNAL | No ControlCommand variant or encoder path | Schema-2 operation with a journaled result |
| Replay | One retry of a non-STATUS request within its deadline | Any sequence at or below the high-water mark is rejected |
| STATUS | Schema-1 request and 64-byte response; Java omits journal bounds from HostStatus | Schema-1 snapshot including retained journal bounds |

The Java client preserves the same request bytes for its retry. In the current
implementation a decoded retry response can end the exchange even when the
first delivery was uncertain; it does not recover a native durable result.
Launch uses the native CLI and a manifest/journal/host handoff probe. Discovery
reads the manifest and observes host and journal state. These implemented
paths do not imply that command routing, result projection, or server-durable
ACK forwarding has been completed.

### Recovery limits and future implementation

The server-durable prefix plus local journal suffix provide recorded operation
and lifecycle evidence. They do not expose the host's complete admission
high-water mark: an admitted operation may have no result record, or its effect
may still be running. Therefore `max(recorded sequence) + 1` is not proven to
be a fresh sequence on reconnect. No native API currently returns that mark.
The orchestration task must resolve this under the existing admission contract;
this documentation does not invent an intent log, replay ledger, or recovery
protocol. Missing results never authorize automatic effect replay.

The host does not restart a failed incarnation to resume its live process tree.
Source-aware controls, addressed process controls, PTY closure events, and
Windows ConPTY remain separate queued work. Their proposed contracts must not
be described as current behavior.

### Verification reference

Native protocol fixtures and Rust tests describe the implemented native bytes.
In particular, `control-idempotency-v2.bin` covers all five operation types,
unsigned sequences above `i64::MAX`, and opaque envelopes with unknown fields.
Older schema-1 operation fixture bytes remain frozen, although the live host
rejects those operation requests. Java alignment acceptance must compare with
the current native fixtures and exercise a real host; documentation updates
alone do not establish that these checks pass.

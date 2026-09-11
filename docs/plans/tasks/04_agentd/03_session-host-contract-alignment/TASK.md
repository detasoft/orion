# Align AgentD with the Stabilized Session-Host Contract

Status: todo (runtime alignment remains; documentation audit completed 2026-09-07)
Parent: ../TASK.md
Session-host review: ../../../../../../../../session-host/MODULE_REVIEW.md

Bring every remaining AgentD session-host integration path to the final native
packaging, control, journal, metadata, and lifecycle contracts after the focused
control model changes are complete. The documentation audit accepts the current
native implementation as the baseline; it did not change Java or Rust runtime
behavior. The linked contract records the shared Java/native interface.

## Scope

- Audit AgentD launch, discovery, control transport, journal projection,
  recovery, retention acknowledgement, and status handling against the current
  session-host protocol and compatibility fixtures.
- Treat `RECEIVED` as transient admission only. Observe operation completion
  through `COMMAND_RESULT` and preserve the documented unknown/partial-effect
  semantics after ambiguous delivery or a missing result record.
- Use the source-aware operation wrapper and exact source envelope without
  restoring a duplicate native command identity, result ledger, or legacy
  operation fallback. Apply replay protection only to `SERVER` sequences.
- Put the operation sequence in the frame header and the explicit source in the
  payload. Use empty or rejected `RECEIVED`; correlate server results by source
  and sequence, while treating manual sequences as live-only values.
- Preserve uncertain delivery across reconnects: a stale rejection cannot
  resolve an earlier attempt. A journal suffix does not expose admissions with
  pending or missing results, so recorded maxima alone do not prove a fresh
  sequence. Resolve allocation with command orchestration before completion.
- Send server retention acknowledgements through the same source-aware
  operation contract and advance them only from a server-durable journal prefix.
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
- No obsolete operation path, duplicate command identity, private
  durable command cursor, or AgentD-owned host termination coordinator remains.

## Boundary

This task owns AgentD conformance with the established native host contracts
and removal of superseded AgentD paths. It does not change the session-host wire
or journal formats, implement server command orchestration, or add local terminal
UI behavior.

---

## Native Control and Journal Contract

Status: reconciled with the source-aware implementation on 2026-09-10.
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

The Rust host and Java client use the same checked-in fixtures and operation
layout. Remaining work in this task concerns higher-level AgentD consumers, not
a second control codec.

### Native operation contract

`INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL` use the
source-aware operation wrapper (schema 3, with schema 4 extending `SIGNAL` by a
process token).
One unsigned sequence in the 32-byte frame header identifies the operation and
correlates its response. Live operation admission accepts values from `1`
through `u64::MAX - 1`; gaps and values above `i64::MAX` are valid.
`u64::MAX` is reserved for responses without an associated sequence.

The little-endian operation payload is:

```text
u16 source                 # 1 SERVER, 2 MANUAL
u16 reserved               # zero
u32 serverEnvelopeLength
serverEnvelopeLength bytes # nonempty for SERVER, empty for MANUAL
command-specific effect bytes
```

For `SERVER`, the nonempty envelope is preserved exactly without decoding its
CBOR contents. For `MANUAL`, the exact complete operation payload is preserved
as the result envelope. There is no separately encoded native CommandId or
payload operation sequence.
`INPUT` retains its 16-byte input UUID and raw bytes; `RESIZE` contains two u32
dimensions; `SIGNAL` contains u16 kind, u16 reserved zero, and i32 platform code;
`TERMINATE` contains only u16 mode and u16 reserved zero; `ACK_JOURNAL` contains
one u64 journal event ID. The complete payload is bounded to 16 MiB.

`STATUS` remains a schema-1 empty request with a 64-byte response. The
`APPEND_EVENT` schema-1 layout is reserved, but the live Unix host rejects it
because ordered harness ingress is not implemented.

### Admission, execution, and journal results

The host keeps only an in-memory accepted-sequence high-water mark for `SERVER`
replay protection. A server sequence at or below it is rejected, including a
byte-identical retry. Every valid `MANUAL` delivery is admitted without reading
or changing that mark, including repeated sequences on one or several
connections. Admission registers one active operation; it does not append a
durable intent or retain a result ledger or manual source state.

The host sends an empty `RECEIVED` before applying the effect. Admission
rejection uses `RECEIVED` with u32 error code and bounded UTF-8 detail.
Failure to deliver the receipt is logged and does not cancel the admitted
effect. `ERROR` handles other control errors. Response frames use schema 1.

After executing an admitted effect once, the host attempts to durably append:

```text
[eventId, COMMAND_RESULT,
 [source, operationSequence, exactSourceEnvelope, outcome, detail]]
```

A live host produces succeeded or failed results. Rejected and ambiguous
outcomes remain values understood by shared journal readers. Admission
rejection does not create a result record. A failed effect can have partial
side effects; `PTY_INPUT` records requested bytes, not confirmed delivery.
A result-append failure is logged to stderr and does not replay the effect.
A missing result therefore means unknown outcome.

Ordinary effects from both sources share a mutex; `TERMINATE` bypasses it to
signal descendants while an ordinary effect is blocked. Sequences identify attempts, not FIFO
positions across connections. Server recovery matches only `SERVER` results by
sequence and ignores manual sequences. A manual client sends an intended effect
once and does not retry an uncertain delivery. The host has no grace timer,
escalation loop, or signal retry.

### Journal acknowledgement and retention

`ACK_JOURNAL` uses the same admission and result path as the other operations.
A server ACK includes its opaque envelope and participates in server sequence
ordering. A manual ACK executes on every valid delivery without a durable retry
identity. A server ACK watermark must represent a complete server-durable
prefix; a network write alone is not authority to delete local history. A
manual client that deliberately sends ACK assumes its retention consequences.
Java server forwarding is still pending.

During effect execution, the host rejects zero watermarks and values beyond
the current journal tail. These effect failures produce failed results when
the journal is writable. A greater valid watermark is published in
`control-retention-state`, containing `stateVersion: 1` and
`acknowledgedEventId`. Publication writes and syncs a temporary file, renames
it, and syncs the directory before newly covered deletion is authorized.
The sidecar is local deletion permission, not an AgentD replication cursor.

Repeated or lower watermarks in newly admitted operations do not lower the
stored watermark. Reusing a server operation sequence is stale; reusing a
manual sequence does not affect watermark validation. The handler
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

### Shared Java and native interface

Sources: `ControlCommand`, `NativeControlCodec`, `ControlResult`, and
`SessionControlClient` under `agentd/src/main/java/pro/deta/orion/agentd/session/`.

| Area | AgentD and native host |
| --- | --- |
| Frame correlation | Operation sequence in the header; query commands retain their query sequence |
| Source prefix | Explicit `SERVER` or `MANUAL`, reserved zero, optional server envelope, effect |
| TERMINATE effect | Four bytes, mode and reserved zero |
| Operation response | `RECEIVED`, empty or carrying an error payload |
| ACK_JOURNAL | Source-aware operation with a journaled result |
| Replay | Server high-water rejection; no manual retry or deduplication state |
| STATUS | Schema-1 snapshot including retained journal bounds |

The Java client performs one exchange and never retries an uncertain operation.
Launch uses the native CLI and a manifest/journal/host handoff probe. Discovery
reads the manifest and observes host and journal state. These implemented
paths do not imply that command routing, result projection, or server-durable
ACK forwarding has been completed.

### Recovery limits and future implementation

The server-durable prefix plus local journal suffix provide recorded server
operation and lifecycle evidence. They do not expose the host's complete
admission high-water mark: an admitted server operation may have no result
record, or its effect
may still be running. Therefore `max(recorded sequence) + 1` is not proven to
be a fresh sequence on reconnect. No native API currently returns that mark.
The orchestration task must resolve this under the existing admission contract;
this documentation does not invent an intent log, replay ledger, or recovery
protocol. Missing results never authorize automatic effect replay, and manual
result sequences are excluded from server recovery.

The host does not restart a failed incarnation to resume its live process tree.
Windows ConPTY remains separate queued work. Source-aware controls, addressed
process controls, and PTY closure events are current contracts.

### Verification reference

Native protocol fixtures and Rust tests describe the implemented native bytes.
In particular, `control-source-aware.bin` covers both sources for all five
operation types, unsigned sequences above `i64::MAX`, and opaque server
envelopes with unknown fields. Java tests compare with the native fixtures and
exercise a real host.

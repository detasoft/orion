# Native Control Journal Idempotency Implementation Record

Status: completed native implementation; documentation reconciled on 2026-09-08.

The current behavior is specified in
[Native Control and Journal Contract](2026-09-03-native-control-journal-idempotency-design.md)
and the [native protocol reference](../../session-host/protocol/README.md).
Remaining Java integration belongs to the
[AgentD alignment task](current-work/04_agentd/03_session-host-contract-alignment/TASK.md).

## Admission and execution

`INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL` use one schema-2
operation path. The frame header carries the unsigned `operationSequence`;
the payload contains the envelope length, exact opaque server CBOR item, and
typed effect. `STATUS` requests and response frames use schema 1.

The live host admits a sequence above its in-memory high-water mark and sends
`RECEIVED`, then executes the effect once. Stale sequences are rejected,
including identical retries. Ordinary effects share a mutex; `TERMINATE`
bypasses it so blocked PTY input cannot prevent process-tree signaling.

After the effect, the host attempts to durably append `COMMAND_RESULT` with
the sequence, exact envelope, outcome, and detail. Receipt delivery and journal
append failures are reported without replaying the effect. A missing result
leaves the outcome unknown; a failed effect may have produced partial effects.
Finalization closes admission and waits for admitted operations before the
final journal flush.

## Acknowledgement and retention

`ACK_JOURNAL = 0x0007` uses the same schema-2 wrapper and operation sequence.
Its effect is one little-endian `u64 eventId` covering a complete server-durable
journal prefix. Admission returns `RECEIVED`; effect completion is observed
through `COMMAND_RESULT` when its append succeeds.

The host validates the watermark against the journal tail and durably publishes
its monotonic value in `control-retention-state` before requesting deletion.
Publication writes and syncs a temporary file, renames it, and syncs the
containing directory. The sidecar remains local deletion permission; the
server owns the replication cursor.

The existing maintenance worker compresses closed segments independently of
acknowledgement. It deletes only a size-selected oldest closed prefix fully
covered by the durable watermark. The active segment and unacknowledged events
remain even when physical size exceeds the target. Maintenance failures do not
revoke durable deletion permission. `RECEIVED` confirms neither watermark
publication nor physical cleanup.

ACK results are journal records and are not covered by the ACK that creates
them. Journal-sync must avoid acknowledgement traffic driven solely by ACK
results.

## Recovery and verification boundaries

The server-durable prefix and local suffix expose recorded command results,
not the complete live admission high-water mark. Recorded maxima alone cannot
allocate a proven fresh sequence after reconnect when a result is pending or
missing. Sequence allocation remains an AgentD orchestration concern. The host
does not resume a failed incarnation or reconstruct effects from old journals.

Native fixtures and tests cover schema-2 operations, unsigned sequences above
`i64::MAX`, opaque envelopes, stale rejection, blocked input with termination,
durable watermark publication, retention, and journal failure behavior. Java
alignment must use those fixtures and a real host. Frozen schema-1 operation
fixtures are historical bytes; live operation requests require schema 2.

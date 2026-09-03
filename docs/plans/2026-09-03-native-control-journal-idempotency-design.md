# Native Control Journal Idempotency Design

Status: approved on 2026-09-03.

## Context

The native `session-host` outlives AgentD connections and keeps the session
process, PTY, and journal running while AgentD disconnects or restarts. AgentD
must recover without a private durable command ledger and without blindly
repeating controls whose responses were lost.

The server is the authority for the journal prefix it has durably committed.
The live host journal is the authority for the later local suffix. Current
physical-size retention compresses closed segments and may delete the oldest
closed prefix without knowing whether the server saved it. That deletion rule
must become acknowledgement-gated.

## Selected Architecture

AgentD assigns one unsigned, monotonically increasing `operationSequence` to
each established-session `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` command.
Gaps are valid. `START_SESSION` and `APPEND_EVENT` remain outside this flow.

Each request carries the sequence, the bounded CommandId, the exact opaque
server CBOR command item, and the existing typed effect payload. Native code
does not decode or re-encode the command item. The complete retry identity is
the sequence, CommandId bytes, command-envelope bytes, control type, and effect
bytes.

Before an external effect, the host durably appends `COMMAND_ACCEPTED`. After
the effect, it durably appends `COMMAND_RESULT` and returns that result record's
`eventId`. An identical completed retry returns the original result `eventId`
without another effect or journal append. Reusing a sequence with different
identity is a conflict, and an unknown sequence below the accepted high-water
mark is stale.

`COMMAND_ACCEPTED` contains the operation sequence and exact command envelope.
`COMMAND_RESULT` contains the operation sequence, separately supplied CommandId,
outcome, and bounded detail. The accepted record does not repeat CommandId:
AgentD already retains the typed command view used during recovery, while the
native host has no reason to interpret the envelope.

The host keeps a bounded in-memory table for accepted operations that the
server has not yet acknowledged. The table distinguishes pending and completed
operations, so reconnects cannot duplicate an effect while its original
handler is still running. The accepted-operation high-water mark remains in
memory after completed details are evicted. A full table rejects a genuinely
new operation before intent or effect while still allowing matching retries and
`STATUS` or `ACK_JOURNAL` controls.

The host incarnation is not restarted to continue a live session. If the host
dies, that session fails instead of reconstructing and resuming its control
ledger. In particular, the host does not synthesize `AMBIGUOUS` results by
scanning old accepted intents. Recovery in this design means AgentD recovery
while the host remains alive.

## AgentD Recovery

After AgentD connects or restarts, the server supplies for each session:

1. its authoritative durably committed journal `eventId`; and
2. the highest `operationSequence` represented by that committed prefix.

AgentD scans the host journal strictly after the server cursor through a stable
tail and observes `COMMAND_ACCEPTED`, `COMMAND_RESULT`, and lifecycle events.
The next operation sequence is one greater than the maximum of the server
prefix and local suffix. Commands wait in the session's bounded serial lane
until this scan completes.

Correct retention guarantees that the server cursor cannot fall below the
local retained floor: every deleted event was covered by a server-confirmed
watermark. A missing or corrupt required suffix blocks only that session;
AgentD never guesses a sequence.

## Durable Journal Acknowledgement

`ACK_JOURNAL` is a non-journaled control command carrying one nonzero journal
`eventId`. AgentD may generate it only after the server confirms that it has
durably saved the complete journal prefix through that ID. Writing bytes to a
network connection is not sufficient.

The host validates that the watermark does not exceed its current logical
journal tail. A greater watermark atomically replaces a versioned
`control-retention-state` sidecar beside the journal. The file contains only
the acknowledged journal `eventId`; it is local retention permission, not an
AgentD cursor, session metadata field, or server-replication authority.

Durable publication is ordered as follows:

1. write the complete next checkpoint to a temporary file;
2. sync the temporary file data;
3. atomically rename it over `control-retention-state`;
4. sync the containing directory; and
5. only then make newly covered segments eligible for deletion.

The host replies `ACCEPTED` only after the new watermark is durable. Repeated
or lower watermarks do not lower state and return the current durable
watermark. A repeated request also wakes maintenance so a prior deletion
failure can be retried.

A crash before checkpoint publication leaves the previous watermark and
therefore cannot authorize new deletion. A crash after publication may leave
eligible files present; later maintenance may finish deleting them. No crash
boundary permits deletion ahead of the durable checkpoint.

## Compression and Retention

Compression remains independent of server acknowledgement. The maintenance
worker may compress every closed raw segment and safely replace it with its
equivalent `.cbor.zst` representation.

Deletion remains an oldest-prefix physical-size policy with an additional
hard gate. A closed segment is eligible only when its last `eventId` is at or
below the durable acknowledged watermark. The active segment is never deleted.
If the size target requires deleting unacknowledged data, maintenance retains
that data and the physical journal is allowed to exceed `journal_max_bytes`.
Absence of a checkpoint means that no segment is eligible for deletion.

The existing single maintenance worker remains the sole segment mutator. It
receives the acknowledged watermark, discovers closed segment event ranges,
compresses closed segments, and deletes only the size-selected eligible prefix.
It must make each deletion and directory update durable. The control layer is
the sole writer of `control-retention-state`; journal maintenance never writes
a second watermark.

## Failure Handling

Malformed v2 controls, zero sequences, invalid CommandIds, empty or oversized
envelopes, future ACK watermarks, conflicting sequence reuse, and unexplained
stale sequences are rejected before external effects. Expected control errors
remain protocol responses rather than process failures.

An I/O failure before `COMMAND_ACCEPTED` leaves the operation unaccepted and
safe for AgentD to retry. Once intent is durable, the host must append and sync
the actual result before reporting success. Loss of the direct response is
harmless because a retry reuses the journaled result.

Checkpoint publication failure leaves the old watermark and forbids newly
eligible deletion. Compression or deletion failure does not invalidate an
already durable server acknowledgement; maintenance records the failure and
retries it without lowering the watermark.

## Compatibility and Scope

The existing v1 control and journal fixtures remain byte-for-byte frozen.
Additive v2 control fixtures and command-event fixtures cover values above
`i64::MAX`, exact unknown CBOR fields, and cross-language decoding.

This work changes the native control protocol, journal event allocation,
in-memory control ledger, acknowledgement sidecar, retention gate, tests,
fixtures, and directly necessary documentation. AgentD generation and retry
timing, server projection, ordered harness-event ingress, and resumption of a
failed host incarnation remain outside this task.

## Testing

Tests cover all four v2 controls, exact owned command-envelope bytes, sequence
gaps, identical pending and completed retries, conflicts, stale sequences,
capacity and ACK-driven eviction, and result `eventId` reuse across AgentD
reconnects.

Retention tests cover no deletion without a checkpoint, compression without an
ACK, deletion only through the acknowledged segment boundary, a size target
that cannot be met without unacknowledged deletion, monotonic repeated and
lower ACKs, future and zero ACK rejection, and deletion retry.

Filesystem seams cover failure before checkpoint rename, durable checkpoint
publication before deletion, and durable deletion afterward. Compatibility
tests keep v1 bytes frozen and verify the additive native and Java fixtures.

## Rejected Alternatives

Journaled ACK records were rejected because they add retention facts to the
replicated stream they acknowledge and can create an ACK feedback cycle.
Memory-only acknowledgement was rejected because a crash between permission
receipt and deletion leaves no durable explanation of what may be removed.
An AgentD-local durable cursor was rejected because AgentD recovery deliberately
uses the server prefix plus host-journal suffix instead of a second local
authority.

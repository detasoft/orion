# Relay Session Journals to Durable Server Storage

Status: todo

## Goal

Read each session's local journal and relay its original records to the server.
The server owns durable storage and returns the EventId it has committed. AgentD
uses that cursor only to resume delivery and to tell `session-host` which local
history the server has safely stored.

## Dependencies

- Completed AgentD HTTP/2 control lifecycle and session discovery.
- Completed paged journal reader (`02e74a3a`).
- Completed EventId-only native retention control and Java codec.
- Completed server replication contract returning the durable EventId cursor
  (`c0b90d95`).

This leaf does not depend on command orchestration. It owns journal resume and
retention acknowledgement only. Command identities, operation sequences,
delivery fencing, and command-result projection remain outside this task.

## State Ownership

- The server journal is the authoritative long-term copy. Its durable EventId
  cursor is the sole replication authority.
- The host's durable acknowledged EventId is local deletion permission only.
- AgentD is a stateless relay: it persists no replication cursor, journal copy,
  acknowledgement queue, or recovery ledger. It obtains the server cursor after
  every reconnect and restart; in-memory positions only bound current delivery.
- Metadata timestamps, host status, and command sequences are never used as
  replication or retention recovery values.
- `ACK_JOURNAL` is a monotonic, idempotent retention control carrying only the
  acknowledged EventId. It has no command source, operation sequence, command
  envelope, or journaled `COMMAND_RESULT`.

## Recovery Contract

After `SESSION_OPEN`, consume the server's durably committed journal EventId
cursor. Send only later records from the host journal. A reconnect repeats the
same exchange and does not reconstruct any AgentD-owned recovery state. A
successful transport write does not establish durability; only the server's
returned cursor does.

The host retention watermark may lag the server cursor. After observing a durable
server cursor, deliver that EventId through `ACK_JOURNAL`. A repeated or lower
acknowledgement is a harmless no-op. If delivery is ambiguous, retain no retry
state; the cursor received after reconnect naturally repeats the acknowledgement.

Pause only the affected session for a corrupt complete record or a host retention
watermark ahead of the server cursor. Server-control claiming and command
admission do not gate journal upload or retention ACK.

## Journal Synchronization

- Send only records strictly after the current server cursor, preserving their
  exact CBOR Sequence bytes, EventIds, unknown types, payloads, and trailing
  fields in bounded batches.
- Interrupted or unacknowledged batches may be resent. Never advance from a
  transient send result.
- Accept only monotonic acknowledgements issued after durable server commit.
- Treat each durable cursor as authority to deliver `ACK_JOURNAL(cursor)` to the
  host. The host durably advances its retention sidecar before reporting success.
- Do not append a journal record for retention acknowledgement; acknowledgement
  must not create more journal data or a replication feedback loop.
- EventIds are strictly increasing but not consecutive. A server cursor below
  the first locally available EventId is not proof of a missing record: relay
  the first available record without manufacturing a gap. Remove the current
  numeric-gap inference from the AgentD reader, server replication path, and
  server journal read API. Verifiable continuity across lost records is
  deferred to [the separate journal-continuity task](../16_journal-continuity.md).
- Backpressure or failure in one session never blocks control heartbeat or
  another session.

## Implementation Plan

1. Add failing synchronization tests for initial catch-up, reconnect, restart,
   original-byte preservation, page bounds, resend, live tailing, unknown
   records, completed sessions, and fair multi-session progress.
2. Correct numeric-gap inference in the AgentD reader, server replication path,
   and server journal read API. Test naturally spaced EventIds with and without
   local retention.
3. Connect the existing journal reader and session transport with bounded,
   per-session pumps. Resume from the server cursor without AgentD persistence.
4. Deliver only server-committed EventIds through the existing `ACK_JOURNAL`
   control. Test reconnect after ambiguous delivery and isolation of host ACK
   failures; keep acknowledgement outside command state.
5. Verify protocol compatibility, AgentD recovery and synchronization, and a
   real native host before full Maven verification.

## Acceptance

- Initial sync, reconnect, and AgentD restart resume strictly after the server's
  durable EventId cursor without persisting AgentD recovery state.
- Server storage, not AgentD transport or local bookkeeping, determines which
  events are durable; AgentD preserves original record bytes during delivery.
- Every durable server cursor can advance the host retention watermark directly;
  duplicate acknowledgements are harmless and ambiguous delivery needs no
  AgentD-owned retry state.
- Journal synchronization neither consumes nor produces command identities,
  operation sequences, envelopes, claims, or command results.
- A host retention watermark ahead of the server cursor or a corrupt complete
  record pauses only the affected session. Numeric EventId jumps are accepted
  without claiming that records were lost.

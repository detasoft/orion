# Synchronize, Resume, and Recover Server Session State

Status: todo

- Owner: codex, session command-orchestration-recovery-d8e4, branch
  `codex/agentd-journal-sync-d8e4`, worktree
  `.worktrees/agentd-journal-sync-d8e4`, started 2026-09-11 10:56 Europe/Amsterdam.

## Goal

Resume each session from the server's durable journal state, stream original
journal records, and translate the server's durable EventId cursor into a
monotonic local retention acknowledgement without owning command state.

## Dependencies

- Completed AgentD HTTP/2 control lifecycle and session discovery.
- Completed paged journal reader (`02e74a3a`).
- Existing native journal-retention sidecar and control support (`5005ae2a`).
- Completed server replication contract returning the durable EventId cursor
  (`c0b90d95`).

This leaf does not depend on command orchestration. It owns journal resume and
retention acknowledgement only. Command identities, operation sequences,
delivery fencing, and command-result projection remain outside this task.

## State Ownership

- The server cursor is the sole replication authority and is always an EventId.
- The host's durable acknowledged EventId is local deletion permission only.
- AgentD persists no replication cursor. It obtains the server cursor after every
  reconnect and restart.
- Metadata timestamps, host status, and command sequences are never used as
  replication or retention recovery values.
- `ACK_JOURNAL` is a monotonic, idempotent retention control carrying only the
  acknowledged EventId. It has no command source, operation sequence, command
  envelope, or journaled `COMMAND_RESULT`.

## Recovery Contract

After `SESSION_OPEN`, consume the server's durably committed journal EventId
cursor. Send only later records. A reconnect repeats the same exchange and does
not reconstruct any additional recovery state.

The host retention watermark may lag the server cursor. After observing a durable
server cursor, deliver that EventId through `ACK_JOURNAL`. A repeated or lower
acknowledgement is a harmless no-op. If delivery is ambiguous, retain no retry
state; the cursor received after reconnect naturally repeats the acknowledgement.

Pause only the affected session for a required-history gap, a corrupt complete
record, or a host retention watermark ahead of the server cursor. Server-control
claiming and command admission do not gate journal upload or retention ACK.

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
- If the server cursor precedes the retained floor, report an integrity gap and
  await the server's decision instead of inventing replacement history.
- Backpressure or failure in one session never blocks control heartbeat or
  another session.

## Implementation Plan

1. Replace the native source-aware `ACK_JOURNAL` operation with an EventId-only,
   monotonic retention control that durably updates the existing sidecar and does
   not append `COMMAND_RESULT`.
2. Update the Java control model and codec to deliver the EventId without a
   source, operation sequence, or command envelope.
3. Add failing synchronization tests for initial catch-up, reconnect, restart,
   original-byte preservation, page bounds, resend, live tailing, gaps, unknown
   records, completed sessions, and fair multi-session progress.
4. Implement session streams and bounded journal pumping without any AgentD
   cursor file.
5. Add failing retention tests for monotonic server commits, repeated and lower
   EventIds, durable sidecar update, reconnect after ambiguous delivery, invalid
   future EventIds, and absence of acknowledgement records in the journal.
6. Verify protocol compatibility, AgentD recovery/sync behavior, and a real
   native host before full Maven verification.

## Acceptance

- Initial sync, reconnect, and AgentD restart resume strictly after the server's
  durable EventId cursor without persisting AgentD recovery state.
- Every durable server cursor can advance the host retention watermark directly;
  duplicate acknowledgements are harmless and ambiguous delivery needs no
  AgentD-owned retry state.
- Journal synchronization neither consumes nor produces command identities,
  operation sequences, envelopes, claims, or command results.
- A host retention watermark ahead of the server cursor pauses only the
  inconsistent session; gaps and corrupt complete records remain explicit.

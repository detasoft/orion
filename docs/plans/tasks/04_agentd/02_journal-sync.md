# Synchronize, Resume, and Recover Server Session State

Status: todo

## Goal

Resume each session from the server's durable journal state, stream original
journal records, and forward server-authorized durable acknowledgements to
`session-host` without owning command sequence allocation or retry.

## Dependencies

- Completed AgentD HTTP/2 control lifecycle and session discovery.
- Completed paged journal reader (`02e74a3a`).
- Completed source-aware native controls and atomic server-control claim
  (`5005ae2a`, `d2ad19d6`, `433b4083`).
- The server replication contract must return the durable journal EventId cursor,
  the server's last allocated per-session `SERVER operationSequence`, and an exact
  authorization item with its own stable sequence for each requested journal ACK.

This leaf no longer depends on command orchestration. It owns journal resume and
ACK forwarding only; the server durably owns command and ACK operation sequences.

## State Ownership

- The server cursor is the sole replication authority and is always an EventId.
- The server's per-session operation sequence is the sole allocation authority
  for `SERVER` commands and ACKs. AgentD neither allocates nor persists it.
- The host's accepted sequence high-watermark provides live duplicate rejection;
  it is not a command-result ledger or a journal cursor.
- The host claim's acknowledged EventId is local deletion permission only.
- AgentD persists none of these values. It obtains the server-owned values after
  every reconnect and restart.
- Metadata timestamps, host status range, and MANUAL sequences are never used as
  recovery values.

## Recovery Contract

After `SESSION_OPEN`, consume one server synchronization response containing:

1. the server's durably committed journal EventId cursor; and
2. the server's last allocated per-session SERVER operation sequence, when present.

Call `CLAIM_SERVER_CONTROL` to fence stale native connections and obtain the
host's current admission and retention observations. Do not derive or advance a
command allocator from either observation. The claim returns exactly two host
facts:

- accepted SERVER sequence high-watermark; and
- applied `ACK_JOURNAL` EventId watermark.

Pause only that session when the claim is ambiguous, the host has accepted a
sequence beyond the server's last allocated value, the local journal has a
required-history gap or corrupt complete record, or the host ACK watermark is
ahead of the server cursor. A successful claim establishes the current fenced
native connection; later commands and ACK instructions retain their exact
server-assigned sequences.

## Journal Synchronization

- Send only records strictly after the current server cursor, preserving their
  exact CBOR Sequence bytes, EventIds, unknown types, payloads, and trailing
  fields in bounded batches.
- Interrupted or unacknowledged batches may be resent. Never advance from a
  transient send result.
- Accept only monotonic acknowledgements issued after durable server commit.
- When the server cursor is ahead of the host ACK watermark, request or consume
  one server-authorized `ACK_JOURNAL` instruction carrying its stable operation
  sequence and exact envelope, then make one native delivery attempt. `RECEIVED`
  proves admission only; observe completion through the journaled result.
- Do not schedule another ACK solely because uploading that ACK's own
  `COMMAND_RESULT` advanced the server cursor.
- If the server cursor precedes the retained floor, report an integrity gap and
  await the server's decision instead of inventing replacement history.
- Backpressure or failure in one session never blocks control heartbeat or
  another session.

## Implementation Plan

1. Extend the backward-readable synchronization protocol and transport callback
   so AgentD receives the durable EventId cursor, the server's last allocated
   operation sequence, and exact server-authorized ACK instructions.
2. Add failing recovery tests for command and journal cursor separation, gaps,
   corruption, missing results, ambiguous claims, inconsistent host values, and
   both claim watermarks.
3. Fence stale native connections through the atomic claim without creating an
   AgentD command allocator or treating the host watermark as durable history.
4. Add failing synchronization tests for initial catch-up, reconnect, restart,
   original-byte preservation, page bounds, resend, live tailing, gaps, unknown
   records, completed sessions, and fair multi-session progress.
5. Implement session streams and bounded journal pumping without any AgentD
   cursor file.
6. Add failing ACK tests for stable server-assigned sequences, monotonic server
   commits, host/server watermark comparison, one native delivery attempt,
   journaled completion, missing-result uncertainty, and feedback suppression.
7. Verify protocol compatibility, AgentD recovery/sync behavior, and a real
   native host before full Maven verification.

## Acceptance

- Initial sync, reconnect, and AgentD restart resume strictly after the server's
  durable EventId cursor without persisting AgentD recovery state.
- The server durably owns per-session operation sequence allocation; AgentD does
  not reconstruct an allocator from the journal or host watermark.
- The atomic claim fences stale native connections and returns admission and
  retention observations without making either a server cursor.
- A higher server cursor sends one recovered ACK; a higher host ACK watermark
  pauses only the inconsistent session.
- Every server-authorized ACK receives one native delivery attempt. Missing
  results remain unknown, duplicate acknowledgements are harmless, gaps are
  explicit, and no ACK-result feedback loop is introduced.

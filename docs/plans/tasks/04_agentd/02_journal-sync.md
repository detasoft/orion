# Synchronize, Resume, and Recover Server Session State

Status: todo

## Goal

Resume each session from the server's durable journal state, stream original
journal records, forward durable acknowledgements to `session-host`, and expose
one recovered `SERVER` control context for later command routing.

## Dependencies

- Completed AgentD HTTP/2 control lifecycle and session discovery.
- Completed paged journal reader (`02e74a3a`).
- Completed source-aware native controls and atomic server-control claim
  (`5005ae2a`, `d2ad19d6`, `433b4083`).
- The server replication contract must return the durable journal EventId cursor
  and the maximum recorded `SERVER operationSequence` covered by that prefix.

This leaf no longer depends on command orchestration. It owns the shared
recovery gate and allocator required by both journal ACK forwarding and
`04_command-orchestration.md`, removing the former dependency cycle.

## State Ownership

- The server cursor is the sole replication authority and is always an EventId.
- The host claim's accepted sequence is the sole live admission authority for
  allocating the next `SERVER operationSequence`.
- The host claim's acknowledged EventId is local deletion permission only.
- AgentD persists none of these values. It reconstructs them after every
  reconnect and restart.
- Metadata timestamps, host status range, and MANUAL sequences are never used as
  recovery values.

## Recovery Contract

After `SESSION_OPEN`, consume one server synchronization response containing:

1. the server's durably committed journal EventId cursor; and
2. the maximum recorded SERVER sequence in that durable prefix, when present.

Preserve the exact synchronization item used to authorize any resulting native
ACK. Scan the local journal strictly after the server cursor through a stable
tail and find the maximum recorded SERVER sequence in the suffix. Unknown
records still participate in cursor progression; MANUAL results do not
participate in SERVER sequence recovery.

Send the unsigned maximum of the prefix and suffix sequence values to
`CLAIM_SERVER_CONTROL`. The claim atomically fences stale server connections and
returns exactly two host facts:

- accepted SERVER sequence high-watermark; and
- applied `ACK_JOURNAL` EventId watermark.

Reject or pause only that session when the claim is ambiguous, the host value is
below the observed floor, the next unsigned sequence is exhausted, the local
suffix has a required-history gap or corrupt complete record, or the host ACK
watermark is ahead of the server cursor. A successful claim initializes one
in-memory allocator at the unsigned successor of the host sequence and makes a
narrow recovered server-control context available to ACK forwarding and command
orchestration.

## Journal Synchronization

- Send only records strictly after the current server cursor, preserving their
  exact CBOR Sequence bytes, EventIds, unknown types, payloads, and trailing
  fields in bounded batches.
- Interrupted or unacknowledged batches may be resent. Never advance from a
  transient send result.
- Accept only monotonic acknowledgements issued after durable server commit.
- When the server cursor is ahead of the host ACK watermark, allocate one SERVER
  sequence and send one source-aware `ACK_JOURNAL` carrying the exact server
  authorization envelope. `RECEIVED` proves admission only; observe completion
  through the journaled result.
- Do not schedule another ACK solely because uploading that ACK's own
  `COMMAND_RESULT` advanced the server cursor.
- If the server cursor precedes the retained floor, report an integrity gap and
  await the server's decision instead of inventing replacement history.
- Backpressure or failure in one session never blocks control heartbeat or
  another session.

## Implementation Plan

1. Extend the backward-readable synchronization protocol and transport callback
   so AgentD receives the durable EventId cursor, optional recorded SERVER
   sequence maximum, and exact encoded authorization item.
2. Add failing recovery tests for prefix/suffix maxima, MANUAL exclusion,
   concurrent tail growth, gaps, corruption, missing results, ambiguous claim,
   inconsistent host values, unsigned exhaustion, and both claim watermarks.
3. Implement the stable suffix scan and atomic claim. Expose one bounded,
   in-memory recovered server-control context rather than separate ACK and
   command allocators.
4. Add failing synchronization tests for initial catch-up, reconnect, restart,
   original-byte preservation, page bounds, resend, live tailing, gaps, unknown
   records, completed sessions, and fair multi-session progress.
5. Implement session streams and bounded journal pumping without any AgentD
   cursor file.
6. Add failing ACK tests for monotonic server commits, host/server watermark
   comparison, journaled completion, missing result uncertainty, and feedback
   suppression. Implement ACK through the recovered context.
7. Verify protocol compatibility, AgentD recovery/sync behavior, and a real
   native host before full Maven verification.

## Acceptance

- Initial sync, reconnect, and AgentD restart resume strictly after the server's
  durable EventId cursor without persisting AgentD recovery state.
- The atomic claim uses recorded history only as a consistency floor and returns
  the authoritative accepted sequence plus applied retention watermark.
- ACK and later server commands share one recovered allocator and cannot bypass
  the claim gate.
- A higher server cursor sends one recovered ACK; a higher host ACK watermark
  pauses only the inconsistent session.
- Missing results remain unknown, duplicate acknowledgements are harmless, gaps
  are explicit, and no ACK-result feedback loop is introduced.

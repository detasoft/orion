# Expose Historical and Live Session Events

Status: todo
Depends on: ../journal-storage/TASK.md, ../session-replication/TASK.md

Give authorized clients one cursor model for historical replay and live session
events without exposing uncommitted data.

## Scope

- Expose raw session events after an optional event ID through the session
  service and an HTTP API backed directly by `SessionJournalStorage.readAfter`.
- Publish ordered events through `LiveEventBroker` only after durable append
  succeeds, and publish nothing from a failed or uncommitted batch.
- Provide a race-free handoff from historical catch-up to live subscription so
  reconnecting clients neither miss nor duplicate committed events.
- Preserve unknown event types and optional record tails for clients that can
  understand newer schemas while allowing older consumers to ignore them.
- Bound slow consumers independently from journal replication and enforce
  session authorization for both historical and live access.
- Test failed append, concurrent subscribe and append, reconnect cursors,
  unknown events, slow consumers, and server restart replay.

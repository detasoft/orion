# Replicate Session Journals over HTTP/2

Status: todo
Depends on: completed server Agent protocol contracts,
../journal-storage/TASK.md

Accept one current logical replication stream per session and resume directly
from the durable server journal after every reconnect.

## Scope

- Process `SESSION_OPEN` with the session ID, first and last available event
  IDs, and state, then return `SESSION_SYNC` with the committed server cursor
  or `null` for a new session.
- Incrementally append the following raw CBOR Sequence records without adding
  a second event DTO or requiring knowledge of every event type.
- Derive the authoritative resume cursor exclusively from durable storage and
  safely accept records resent after an interrupted append.
- Acknowledge each committed batch with a monotonic journal-timestamp watermark
  only after its records are durably stored; keep no independent cursor that
  can advance ahead of storage.
- Detect missing retained history from the advertised first available event,
  record an explicit integrity gap, and decide how synchronization proceeds
  before requesting any replacement history.
- Use HTTP/2 flow control for storage backpressure and allow fair concurrent
  catch-up of live and completed sessions with disposable physical streams.
- Test disconnect at multiple persistence boundaries, acknowledgement timing,
  reconnect resume, duplicate and conflicting records, gaps, unknown events,
  and server restart.

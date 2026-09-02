# Synchronize and Resume Journal Events

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: ../http2-transport/TASK.md, ../session-discovery/TASK.md,
../journal-reader/TASK.md

Pump durable local records to the server with bounded batching, durable
acknowledgements, and application-level reconnect resume.

## Scope

- Maintain an asynchronous logical pump and sync state for each discovered
  session without requiring a dedicated platform thread.
- Batch `SESSION_EVENTS` by configurable bytes, records, and latency while
  preserving timestamps, event types, schema versions, and payload bytes.
- Advance acknowledged state only after `SESSION_ACK` durable receipt and use
  the server's resume position as authoritative after reconnect.
- Send `SESSION_GAP` when the requested cursor predates retained history, then
  continue from the first available event.
- Test catch-up and live tailing, reconnect resend, stale local cache, delayed
  acknowledgements, gaps, unknown events, high output, and fair multi-session
  progress.

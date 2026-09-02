# Synchronize and Resume Journal Events

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: ../http2-transport/TASK.md, ../session-discovery/TASK.md,
../journal-reader/TASK.md

Pump raw session-host journal records to the server and resume from the
server's durable event ID after every reconnect.

## Scope

- Open one current HTTP/2 stream for each syncing session, send `SESSION_OPEN`
  with its available range, and begin after the server's `SESSION_SYNC` cursor.
- Stream the original CBOR Sequence records in bounded chunks while preserving
  event IDs, unknown event types, payloads, and optional trailing fields.
- Keep no required acknowledgement cursor; after reconnect, discard transport
  state, use the server cursor, and safely resend any uncommitted records.
- Advertise the first available event so the server can record a retention gap
  and request synchronization from the oldest record that remains.
- Test catch-up and live tailing, reconnect resend, duplicates, gaps, unknown
  events, high output, completed sessions, and fair multi-session progress.

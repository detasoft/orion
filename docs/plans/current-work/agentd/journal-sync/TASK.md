# Synchronize and Resume Journal Events

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md

## Goal

Resume each session from the server's durably committed journal timestamp and
forward durable commit acknowledgements to `session-host` for safe retention.

## Dependencies

- Completed AgentD HTTP/2 transport and session discovery.
- Completed paged journal reader.
- Native-host `ACK_JOURNAL` compatibility contract from
  the [control-journal idempotency design](../../../2026-09-03-native-control-journal-idempotency-design.md).

## Requirements

- Open one current HTTP/2 stream for each syncing session, send `SESSION_OPEN`
  with its available range, and accept the server's `SESSION_SYNC` cursor as
  the sole replication authority after every connect or reconnect.
- Send only records whose journal timestamp is strictly after that cursor. The
  cursor is protocol journal order, not a metadata snapshot timestamp.
- Stream the original CBOR Sequence records in bounded chunks while preserving
  event IDs, unknown event types, payloads, and optional trailing fields.
- Accept only monotonic batch acknowledgements issued after durable server
  commit and forward their exact watermark to the matching host as
  `ACK_JOURNAL`.
- Never journal `ACK_JOURNAL`, persist an AgentD cursor, read cursor state from
  a session directory, or treat the host retention watermark as replication
  authority.
- When the server cursor precedes the locally retained floor, report an
  explicit integrity gap and pause that session until the server decides how
  synchronization proceeds.

## Acceptance

- Initial sync, reconnect, and AgentD restart resume strictly after the
  server's durably committed cursor.
- Interrupted or unacknowledged batches are resent without skipping records,
  and duplicate monotonic acknowledgements are harmless.
- Acknowledgements reach the host only after durable server commit and complete
  only after the host durably applies its retention state.
- No AgentD cursor file or session-directory cursor dependency is introduced.
- A cursor below the retained floor reports an integrity gap and sends no
  replacement history until the server responds.
- Catch-up, live tailing, unknown events, high output, completed sessions, and
  fair multi-session progress remain covered.

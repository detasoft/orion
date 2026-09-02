# Define the Server Agent Protocol Contracts

Status: todo
Journal contract: ../../../2026-09-02-session-journal-cbor-sequence.md

Define the shared control and session-stream contracts before transport and
storage implementations depend on them.

## Scope

- Assign stable message type IDs and encode every control message as a CBOR
  array whose existing positions never change meaning and whose optional tail
  may be ignored by older readers.
- Define `HELLO`, `WELCOME`, session discovery, status, command, `SESSION_OPEN`,
  and `SESSION_SYNC` messages with explicit bounds and version negotiation.
- Model agent, instance, session, event, and command identities independently
  from HTTP/2 connection and stream IDs.
- Incrementally decode CBOR Sequences across arbitrary DATA frame boundaries
  while preserving unknown journal event types, payloads, and trailing fields.
- Publish golden fixtures and compatibility tests shared by server, AgentD,
  and session-host implementations, including unknown tails and message IDs.

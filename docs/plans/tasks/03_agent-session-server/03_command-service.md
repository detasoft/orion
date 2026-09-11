# Route Commands to Connected Agents

Status: todo
Depends on: completed server control and registries (`3e4a6156`, `3758705a`, `490b458b`)

Send session commands only through the authoritative AgentD connection and
separate transient delivery results from durable journal confirmation.

## Scope

- Implement `START_SESSION`, `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` with
  stable command IDs, server-assigned per-session operation sequences, and
  validated agent, session, payload, and lifecycle state.
- Durably assign an operation sequence before delivering each established-session
  command and preserve that sequence with its command ID across connection
  replacement. A server redelivery uses the same identities; AgentD makes one
  native delivery attempt for each received delivery and has no retry loop.
- Process transient command results for responsive clients without treating
  acceptance or a stale-sequence rejection as durable session history. Complete
  commands only from replicated journal results; a missing result remains unknown.
- Test active-connection routing, takeover races, stable redelivery after
  disconnect, duplicate or missing results, invalid or exited sessions, and
  journal confirmation.

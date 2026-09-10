# Route Commands to Connected Agents

Status: todo
Depends on: completed server control and registries (`3e4a6156`, `3758705a`, `490b458b`)

Send session commands only through the authoritative AgentD connection and
separate transient delivery results from durable journal confirmation.

## Scope

- Implement `START_SESSION`, `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` with
  stable command IDs and validated agent, session, payload, and lifecycle state.
- Preserve pending command IDs across connection replacement so retry uses
  at-least-once delivery and AgentD/session-host deduplication prevents repeats.
- Process transient command results for responsive clients without treating
  acceptance as durable session history.
- Test active-connection routing, takeover races, retry after disconnect,
  duplicate results, invalid or exited sessions, and journal confirmation.

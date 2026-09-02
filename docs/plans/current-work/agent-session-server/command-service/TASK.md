# Route Commands to Connected Agents

Status: todo
Depends on: ../control-and-registries/TASK.md

Send session commands only through the authoritative AgentD connection and
separate transient delivery results from durable journal confirmation.

## Scope

- Implement `START_SESSION`, `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` with
  stable command IDs and validated agent, session, payload, and lifecycle state.
- Preserve pending command IDs across connection replacement so retry uses
  at-least-once delivery and AgentD/session-host deduplication prevents repeats.
- Process transient command results for responsive clients without treating
  acceptance as durable session history.
- Correlate journal evidence such as `PTY_INPUT`, resize, and process events
  with commands where the event schema provides a command ID.
- Test active-connection routing, takeover races, retry after disconnect,
  duplicate results, invalid or exited sessions, and journal confirmation.

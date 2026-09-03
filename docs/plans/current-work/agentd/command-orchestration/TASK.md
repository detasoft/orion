# Route Server Session Commands

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: completed AgentD HTTP/2 transport,
../session-discovery/TASK.md, ../session-runtime-and-control/TASK.md

Validate and route server commands to the correct runtime or host and report
their actual outcome.

- [ ] Route server session commands.
  - Owner: codex, session 32cbac98, started 2026-09-03 19:35 Europe/Amsterdam.

## Scope

- Dispatch `START_SESSION`, `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` by
  command and session identity.
- Validate policy, lifecycle state, payload bounds, terminal dimensions, and
  runtime or workspace selection before local delivery.
- Wait for durable host initialization before `SESSION_STARTED` and for host
  responses before `COMMAND_RESULT`; do not infer exit from control status.
- Handle duplicate commands, missing or exited sessions, reconnect retries,
  and concurrent commands with deterministic per-session ordering.
- Test every command's happy path plus duplicates, invalid state, unknown
  session, delivery failure, host reconnect, and process exit via journal.

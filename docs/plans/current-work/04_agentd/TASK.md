# Build the AgentD Orchestration Service

Status: todo
Detailed plan: ../../2026-09-02-agentd.md
Server counterpart: ../03_agent-session-server/TASK.md

Build a long-lived JVM service that connects CI/CD machines to the central
server while keeping every `session-host` and its child process independent of
the AgentD lifecycle.

## Scope

- Authenticate a server-launched process and maintain one outbound, versioned
  HTTP/2 connection.
- Discover, launch, control, and recover local sessions without owning their
  PTYs or process trees.
- Synchronize durable journal events through per-session streams with durable
  server cursors, gaps, reconnect, and idempotent resume.
- Isolate control, heartbeat, and sessions from output backpressure and
  per-session failures.
- Report machine status and capabilities and support safe service shutdown.

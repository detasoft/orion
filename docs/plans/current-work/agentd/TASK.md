# Build the AgentD Orchestration Service

Status: todo
Detailed plan: ../../2026-09-02-agentd.md
Server counterpart: ../agent-session-server/TASK.md

Build a long-lived JVM service that connects CI/CD machines to the central
server while keeping every `session-host` and its child process independent of
the AgentD lifecycle.

## Scope

- Register the machine and maintain one outbound, versioned HTTP/2 connection.
- Discover, launch, control, and recover local sessions without owning their
  PTYs or process trees.
- Synchronize durable journal events through per-session streams with durable
  server cursors, gaps, reconnect, and idempotent resume.
- Isolate control, heartbeat, and sessions from output backpressure and
  per-session failures.
- Report machine status and capabilities and support safe service shutdown.

## Child Tasks

- [ ] [Persist identity and implement registration](identity-and-registration/TASK.md)
- [ ] [Discover and recover local sessions](session-discovery/TASK.md)
- [ ] [Launch and control session hosts](session-runtime-and-control/TASK.md)
- [ ] [Read session journals](journal-reader/TASK.md)
- [ ] [Synchronize and resume journal events](journal-sync/TASK.md)
- [ ] [Route server session commands](command-orchestration/TASK.md)
- [ ] [Add platform status and lifecycle resilience](platform-status-and-resilience/TASK.md)
- [ ] [Package AgentD and verify MVP acceptance](release-and-acceptance/TASK.md)

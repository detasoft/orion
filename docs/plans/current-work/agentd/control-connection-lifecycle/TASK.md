# Complete the AgentD Control Connection Lifecycle

Status: todo
Design: ../../../2026-09-02-agentd-server-launched-identity-design.md
Server counterpart: [Server control work](../../agent-session-server/control-and-registries/TASK.md)
Next available task: [Handshake and authentication](handshake-and-authentication/TASK.md).

Extend the existing initial `HELLO`/`WELCOME` exchange into a reusable
authenticated control lifecycle with reconnect, heartbeat, and session reports.

## Child Tasks

- [ ] [Complete control handshake and authentication](handshake-and-authentication/TASK.md)
- [ ] [Maintain reconnect and heartbeat](reconnect-and-heartbeat/TASK.md)
- [ ] [Report discovered sessions over the control connection](session-discovery-and-reporting/TASK.md)
- [ ] [Assemble and verify the AgentD control runtime](runtime-and-acceptance/TASK.md)

## Boundaries

- Handshake comes first. Reconnect/heartbeat and session reporting can then
  proceed independently; runtime assembly and shared acceptance follow both.
- Reuse `AgentHandshake`, `AgentControlService`, `JettyHttp2Transport`, and the
  existing discovery registry and monitor. Keep one control lifecycle owner.
- Preserve server-assigned launch identity, process-local credentials, and
  session-host independence. An AgentD process does not create its own agent
  registration or persist credentials or replication cursors.
- Implement these tasks without waiting for journal replication or command
  execution. Their existing tasks consume the authenticated control lifecycle.
- Platform metrics and resilience under combined command/journal load remain
  in ../platform-status-and-resilience/TASK.md. Packaged remote deployment and
  full session MVP acceptance remain in their existing tasks.

# Complete the AgentD Control Connection Lifecycle

Status: todo
Server counterpart: completed server control work (`3e4a6156`, `3758705a`, `490b458b`)

Extend the existing initial `HELLO`/`WELCOME` exchange into a reusable
authenticated control lifecycle with reconnect, heartbeat, and session reports.

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
  in ../06_platform-status-and-resilience.md. Packaged remote deployment and
  full session MVP acceptance remain in their existing tasks.

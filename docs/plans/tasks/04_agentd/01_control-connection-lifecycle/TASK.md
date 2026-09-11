# Complete the AgentD Control Connection Lifecycle

Status: todo
Server counterpart: completed server control work (`3e4a6156`, `3758705a`, `490b458b`)

Complete session reporting and runtime acceptance on top of the implemented
authenticated control lifecycle, reconnect loop, and heartbeat.

## Boundaries

- Authenticated handshake, reconnect with bounded exponential jitter, and
  heartbeat are implemented by the existing control lifecycle owner. Session
  reporting remains next; runtime assembly and shared acceptance follow it.
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

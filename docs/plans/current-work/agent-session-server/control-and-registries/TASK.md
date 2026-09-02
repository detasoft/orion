# Implement Control Streams and Registries

Status: todo
Depends on: ../protocol-contracts/TASK.md, ../session-replication/TASK.md

Integrate authenticated AgentD connections with durable logical agent and
session metadata while keeping connection state transient.

## Scope

- Accept the long-lived bidirectional control stream on the existing Jetty
  HTTP/2 server and require `HELLO` before replying with `WELCOME`.
- Maintain stable agent registration, last seen instance, version,
  capabilities, machine information, connection status, and last-seen time.
- Make the newest authenticated connection authoritative for one `AgentId`,
  mark the old connection obsolete, and route no further work through it.
- Request and reconcile `SESSION_LIST` after each connection, update lightweight
  session metadata, and prefer durable process events over transient status.
- Bind every session stream to its authenticated agent and reject sessions not
  reported by or belonging to that agent.
- Test version rejection, takeover during a partition, spoofed session streams,
  reconnect reconciliation, completed sessions, and server restart recovery.

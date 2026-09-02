# Implement Control Streams and Registries

Status: todo
Depends on: completed server Agent protocol contracts,
../session-replication/TASK.md

Integrate authenticated AgentD connections with durable logical agent and
session metadata while keeping connection state transient.

## Scope

- Accept the long-lived bidirectional control stream on the existing Jetty
  HTTP/2 server and require `HELLO` before replying with `WELCOME`.
- Maintain the server-provisioned `AgentId`, launch generation and ID, last
  seen instance, version, capabilities, machine information, connection
  status, and last-seen time.
- Atomically consume a short-lived, single-use launch permit bound to the
  agent, generation, and launch, then return a reconnect token whose hash and
  expiry are durable while its plaintext remains only in AgentD memory.
- Reject expired, replayed, mismatched, or superseded credentials and invalidate
  every older credential when the server advances the launch generation.
- Make the newest authenticated connection authoritative for one `AgentId`,
  mark the old connection obsolete, and route no further work through it.
- Request and reconcile `SESSION_LIST` after each connection, update lightweight
  session metadata, and prefer durable process events over transient status.
- Bind every session stream to its authenticated agent and reject sessions not
  reported by or belonging to that agent.
- Test permit replay and expiry, generation fencing, reconnect after server
  restart, version rejection, takeover during a partition, spoofed session
  streams, reconnect reconciliation, and completed sessions.

# Implement Server-Side Agent Connection and Registration

Status: todo
Design: ../../../2026-09-02-agentd-server-launched-identity-design.md
Agent counterpart: [AgentD control lifecycle](../../04_agentd/01_control-connection-lifecycle/TASK.md)

Integrate authenticated AgentD connections with durable logical agent and
session metadata while keeping connection state transient. The server creates
each agent identity before launch; AgentD authenticates that existing identity.

## Boundaries

- Transport and durable records are independent starting points. Authentication
  consumes both; connection ownership and session reconciliation follow it.
- Complete this server-side work before session replication. Provide the
  authenticated session context here; verify binding of real replication
  streams in ../02_session-replication.md when those streams are implemented.
- Reuse the existing Agent protocol, server lifecycle, and provisioning
  contracts. AgentD reconnect scheduling, heartbeat production, and session-list
  reporting belong to the agent side.
- Keep command execution, journal replication, remote administration, and full
  packaged MVP acceptance in their existing task nodes.

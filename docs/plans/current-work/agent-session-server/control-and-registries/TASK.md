# Implement Server-Side Agent Connection and Registration

Status: todo
Design: ../../../2026-09-02-agentd-server-launched-identity-design.md
Agent counterpart: ../../agentd/TASK.md
Next available task: [HTTP/2 control transport](http2-control-transport/TASK.md).
Also ready: [Durable agent and launch records](agent-and-launch-records/TASK.md).

Integrate authenticated AgentD connections with durable logical agent and
session metadata while keeping connection state transient. The server creates
each agent identity before launch; AgentD authenticates that existing identity.

## Child Tasks

- [ ] [Add HTTP/2 control transport](http2-control-transport/TASK.md)
- [ ] [Persist agent and launch records](agent-and-launch-records/TASK.md)
- [ ] [Authenticate agent launches and reconnects](launch-and-reconnect-authentication/TASK.md)
- [ ] [Track authoritative connections and health](connection-ownership-and-health/TASK.md)
- [ ] [Reconcile sessions and enforce ownership](session-reconciliation-and-ownership/TASK.md)
- [ ] [Integrate the server runtime and provisioning contracts](runtime-and-provisioning-integration/TASK.md)

## Boundaries

- Transport and durable records are independent starting points. Authentication
  consumes both; connection ownership and session reconciliation follow it.
- Complete this server-side work before session replication. Provide the
  authenticated session context here; verify binding of real replication
  streams in ../session-replication/TASK.md when those streams are implemented.
- Reuse the existing Agent protocol, server lifecycle, and provisioning
  contracts. AgentD reconnect scheduling, heartbeat production, and session-list
  reporting belong to the agent side.
- Keep command execution, journal replication, remote administration, and full
  packaged MVP acceptance in their existing task nodes.

# Assemble and Verify the AgentD Control Runtime

Status: todo
Depends on: all preceding sibling tasks.
Server acceptance dependency: [Runtime integration](../../03_agent-session-server/01_control-and-registries/05_runtime-and-provisioning-integration.md)

Wire authentication, reconnect, heartbeat, and discovery reporting into the
real AgentD entry point and prove the shared server-agent control contract.

## Scope

- Extend `Agent.create` and the existing lifecycle to assemble the completed
  control services and discovery resources after acquiring the process lock.
- Keep process lifecycle distinct from online/offline connection state. Register
  callbacks once and preserve discovery and token ownership across reconnects.
- Order startup, partial-start cleanup, and shutdown so retry workers, timers,
  transport, and discovery close before the process lock is released.
- Verify TLS trust and handshake configuration through the actual entry point;
  do not replace server authentication or certificate verification in production.
- Document the resulting launch, reconnect, rejected-credential, and shutdown
  behavior. Leave journal delivery and command execution to their own tasks.

## Acceptance

- A real AgentD and Orion server complete initial authentication, heartbeat,
  session-list reconciliation, network reconnect, and server-restart reconnect.
- Cover generation revocation, startup failure, shutdown during reconnect, and
  continued discovery while offline. Closing or replacing AgentD leaves existing
  session-host processes and journals intact.
- AgentD-driven acceptance extends the server task's live-peer checks; server
  runtime implementation does not depend on this task, avoiding a dependency cycle.

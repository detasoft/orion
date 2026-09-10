# Integrate the Server Runtime and Provisioning Contracts

Status: todo
Depends on: all preceding sibling tasks
Related: ../../09_remote-machine-provisioning/01_administration-and-end-to-end-acceptance.md

Wire the server control path into Orion startup and existing remote-launch
contracts, and verify it through the real HTTPS listener.

## Scope

- Compose durable records, transport, authentication, connection ownership,
  and session reconciliation in the existing Orion runtime and shutdown order.
- Back `AgentdLaunchAttemptSource` with durable generation advancement and
  permit issuance; back `AgentdAvailability` with authoritative launch-specific
  connection health. Reuse the existing provisioning recovery implementation.
- Ensure credential state is committed before handing a permit to the SSH
  launcher, and that a connection from an old launch cannot satisfy a new
  launch's startup wait.
- Document the required HTTPS trust and server configuration. Machine
  administration, secret-resolution workflows, and packaged remote deployment
  remain in the linked provisioning task.

## Acceptance

- The current real AgentD completes its first authenticated handshake against
  Orion's listener. A live protocol peer verifies reconnect, server restart,
  session reconciliation, heartbeat expiry, and generation replacement.
- Startup failure and shutdown release server resources. Full AgentD-driven
  reconnect and packaged remote-session acceptance follow when the agent-side
  lifecycle and the existing MVP acceptance tasks are implemented.

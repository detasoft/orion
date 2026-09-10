# Persist Launch Generations and Permits

Status: todo
Depends on: registered-agent registry foundation committed in `6648e57c`.
Design: ../../../../2026-09-02-agentd-server-launched-identity-design.md
Plan: ../../../../2026-09-09-agent-server-durable-records.md

Make each server-controlled AgentD launch a durable generation that atomically
fences every prior launch.

## Scope

- Allocate a strictly higher positive generation and a new `LaunchId` in the
  existing agent record while revoking all prior permit and reconnect state.
- Install only a bounded digest and expiry for the current launch permit after
  recovery has made that launch safe; random issuance and hashing policy remain
  with authentication and provisioning consumers.
- Guard launch-specific updates by generation and `LaunchId`, reject generation
  overflow, and serialize concurrent allocations through the existing store
  owner.
- Preserve explicit failure and indeterminate-publication outcomes from the
  registry foundation.

## Acceptance

- Tests cover first and replacement launches, concurrent allocation without
  lost or reused generations, overflow, stale updates, restart recovery, and
  revocation of every previous credential.
- A failed or indeterminate allocation never returns usable launch or permit
  material to its caller.

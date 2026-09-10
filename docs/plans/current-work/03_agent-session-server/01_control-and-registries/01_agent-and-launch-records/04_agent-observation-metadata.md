# Persist Agent Observation Metadata

Status: todo
Depends on: reconnect credential state committed in `1a34cbda`.
Design: ../../../../2026-09-02-agentd-server-launched-identity-design.md
Plan: ../../../../2026-09-09-agent-server-durable-records.md

Retain bounded historical AgentD observations without turning persisted metadata
into connection authority.

## Scope

- Persist the last observed `AgentInstanceId`, agent version, capabilities,
  machine information, and server observation time in the existing agent record.
- Guard updates by the current generation and `LaunchId` so delayed work from an
  obsolete launch cannot overwrite its replacement.
- Preserve observations across close/reopen while deriving online status only
  from the later connection-ownership component.
- Reuse the registry foundation's validation, publication, and failure model;
  introduce no observation store or independent liveness state.

## Acceptance

- Tests cover initial and replacement observations, bounded metadata, stale
  launch rejection, concurrent updates, restart recovery, and publication
  failures.
- Reopened historical metadata never marks an agent online or restores a
  physical connection.

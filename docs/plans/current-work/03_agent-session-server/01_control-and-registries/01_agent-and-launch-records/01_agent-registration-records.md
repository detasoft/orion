# Persist Registered Agent Records

Status: todo
Depends on: completed Agent identity types and the server-launched identity design.
Design: ../../../../2026-09-02-agentd-server-launched-identity-design.md
Plan: ../../../../2026-09-09-agent-server-durable-records.md

Create the server-owned durable registry and the complete immutable agent record
shape before adding launch-state mutations.

## Scope

- Persist stable `AgentId` and bounded server-owned registration metadata in one
  canonical, versioned record whose optional launch, credential, and observation
  fields are defined once for the remaining leaves.
- Use one concrete filesystem owner with a lifetime root lock. Publish complete
  snapshots through forced temporary files, atomic replacement, and directory
  durability without a non-atomic fallback.
- Make duplicate creation preserve existing state and keep independent agents
  isolated. Reject malformed, truncated, oversized, unsupported, or
  identity-mismatched records during recovery.
- Distinguish missing records, conflicts, storage failures, and indeterminate
  post-publication outcomes without introducing an unrestricted save operation.

## Acceptance

- Tests cover creation, duplicate creation, independent agents, close/reopen,
  corrupt and bounded input, ignored temporary files, and exclusive-root
  ownership including path aliases.
- Injected failures before and after publication never return a false durable
  success; an indeterminate owner refuses further operations until reopen.

# Discover and Recover Local Sessions

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: completed AgentD contracts and build, native session-host journal
contracts

Reconstruct AgentD's local session registry entirely from durable session
directories after startup or notification loss.

## Scope

- Scan the configured session root, validate metadata and endpoint descriptors,
  and determine host liveness, child state, and journal readability.
- Represent each discovered session as a replaceable in-memory cache over its
  durable directory and classify incomplete, lost, and degraded sessions.
- Use `WatchService` only to wake reconciliation; perform full rescans after
  `OVERFLOW`, missed events, and periodically during normal operation.
- Detect concurrent directory creation and atomic metadata replacement without
  publishing partially initialized sessions.
- Test empty and populated startup, AgentD restart, invalid metadata, dead and
  live hosts, missed notifications, overflow, and independent session failure.

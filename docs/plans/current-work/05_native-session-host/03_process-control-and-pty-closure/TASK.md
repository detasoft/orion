# Expose Process Control and PTY Closure

Status: todo
Depends on: completed Unix process host and the
[control-journal idempotency design](../../../2026-09-03-native-control-journal-idempotency-design.md).

Keep a live `session-host` as the sole declaration that a session is running,
while exposing its owned processes for inspection and addressed signalling.

- Owner: codex, session native-process-control-47c2, started 2026-09-04 01:41 Europe/Amsterdam.

## Scope

- Expose owned-process snapshots and safe addressed signalling through the
  existing process owner.
- Report PTY closure independently of process-tree liveness and keep process
  controls available while owned processes remain.
- Preserve one control execution path and journal without adding persisted
  session lifecycle state.

## Boundary

This task defines the common journal and control behavior. Linux pidfd/cgroup
mechanics and platform-specific descendant-discovery hardening remain in
`../01_linux-process-tree-control.md`.

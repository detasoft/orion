# Expose Process Control and PTY Closure

Status: paused
Depends on: [Linux process-tree control](../01_linux-process-tree-control.md),
completed Unix process host and the
[control-journal idempotency design](../../../2026-09-03-native-control-journal-idempotency-design.md).

Keep a live `session-host` as the sole declaration that a session is running,
while exposing its owned processes for inspection and addressed signalling.

Next: resume the remaining common-control work after the preserved Linux work
has been reconciled. The earlier design is in branch
`codex/linux-process-tree-control-47c2`, worktree
`.worktrees/linux-process-tree-control-47c2`, at `79386060`.
That branch contains substantial Linux changes, but no LIST_PROCESSES request
or PTY_CLOSED event implementation. Its preservation and progress are recorded
in the linked Linux task; this composite holds no execution claim.

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

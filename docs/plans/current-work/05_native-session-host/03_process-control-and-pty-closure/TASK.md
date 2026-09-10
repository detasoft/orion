# Expose Process Control and PTY Closure

Status: todo
Depends on: completed Linux process ownership `c0a764d1`,
completed Unix process host and the
[control-journal idempotency design](../../../2026-09-03-native-control-journal-idempotency-design.md).

Keep a live `session-host` as the sole declaration that a session is running,
while exposing its owned processes for inspection and addressed signalling.

LIST_PROCESSES and addressed signalling are integrated in `10e92141`.
Next: implement PTY_CLOSED against the integrated Linux owner and current
source-aware control path. The earlier design is in branch
`codex/linux-process-tree-control-47c2`, worktree
`.worktrees/linux-process-tree-control-47c2`, at `79386060`.
That branch contains useful PTY-closure design but no PTY_CLOSED event
implementation. This composite holds no execution claim.

## Scope

- Expose owned-process snapshots and safe addressed signalling through the
  existing process owner.
- Report PTY closure independently of process-tree liveness and keep process
  controls available while owned processes remain.
- Preserve one control execution path and journal without adding persisted
  session lifecycle state.

## Boundary

This task defines the common journal and control behavior. Linux pidfd/cgroup
mechanics are integrated in `c0a764d1`; real modern delegated-cgroup acceptance
remains in `../01_linux-process-tree-control.md` and does not block this common work.

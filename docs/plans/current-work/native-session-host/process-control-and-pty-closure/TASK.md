# Expose Process Control and PTY Closure

Status: todo
Depends on: completed Unix process host,
../control-journal-idempotency/TASK.md

Keep a live `session-host` as the sole declaration that a session is running,
while exposing its owned processes for inspection and addressed signalling.

## Scope

- Add a read-only `LIST_PROCESSES` control request returning a current snapshot
  of the processes owned by the session.
- Define a safe session-scoped process identity that remains protected against
  operating-system PID reuse and is accepted by an addressed `SIGNAL` request.
- Validate that a signal target is still the same owned process immediately
  before delivery; never signal an arbitrary or recycled PID.
- Keep `TERMINATE` as the operation that targets the complete owned process
  set.
- Add an empty `PTY_CLOSED` journal event exactly once after the last
  `PTY_OUTPUT`; terminal output must never appear after it.
- Reject `INPUT` and `RESIZE` after `PTY_CLOSED`, while continuing to serve
  process listing, addressed signals, termination, and harness event ingress.
- Keep the host alive while any owned process remains and exit after the last
  process has been reaped; do not persist a redundant session lifecycle state.
- Cover root exit with a PTY-holding descendant, PTY closure with detached
  processes, stale process targets, addressed signal delivery, command races,
  and final host exit.

## Boundary

This task defines the common journal and control behavior. Linux pidfd/cgroup
mechanics and platform-specific descendant-discovery hardening remain in
`../linux-process-tree-control/TASK.md`.

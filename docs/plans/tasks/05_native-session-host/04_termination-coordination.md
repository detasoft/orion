# Keep Session Termination Explicit

Status: todo
Depends on:
completed process control and PTY closure (`10e92141`, `dcebb944`),
completed explicit journal durability

Keep `TERMINATE` as an explicit operation submitted by the server or a manual
control client. `session-host` executes the requested signal delivery once and
does not own grace periods, escalation deadlines, or retry policy.

## Scope

- Route a valid `TERMINATE` request through the same sequence high-water mark
  as other operation controls.
- Make the termination effect available while another connection has a blocked
  `INPUT`; it must not wait indefinitely for the ordinary operation-order
  mutex.
- Record one `COMMAND_RESULT` for the termination attempt. A missing result
  leaves delivery unknown and is reported as a journal failure, not converted
  into a host-side retry.
- Forward an OS signal received by the host once to the current process tree
  without doing locks, allocation, journal writes, or process traversal in the
  signal handler.
- Keep control connections anonymous and multi-purpose. Decode each frame
  before applying its operation semantics.
- Keep process discovery, PID identity checks, reaping, and final journal
  synchronization in their existing owners.

## Acceptance

- A `TERMINATE` request on one connection completes while a large `INPUT` on
  another connection is blocked by a stopped or non-reading PTY.
- Repeated or stale sequences are rejected by the high-water mark and do not
  execute another signal effect.
- Graceful and force termination are distinct explicit operations; the host
  does not schedule one from the other.
- Signal attempts, command results, process exit, and final journal flush are
  covered without requiring physical journal order to match operation order.

## Boundary

This task owns termination entry and its interaction with operation admission.
Addressed `SIGNAL`, process-list, and PTY-closure contracts are integrated in
`10e92141` and `dcebb944`; Linux cgroup, pidfd, and descendant delivery
mechanics are integrated in `c0a764d1`. Their outstanding real cgroup
validation remains in `01_linux-process-tree-control.md`.

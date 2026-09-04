# Coordinate Session Termination

Status: todo
Depends on:
[process control and PTY closure](../process-control-and-pty-closure/TASK.md),
[explicit journal durability](../journal-durability/TASK.md)

Keep the framed `TERMINATE` request as the normal session shutdown path while
providing process-level `SIGTERM` to `session-host` as a control-socket-independent
manual fallback.

## Scope

- Route both a valid `TERMINATE` request and host-directed `SIGTERM` through one
  idempotent termination coordinator.
- Preserve `TERMINATE` payload, response, journal, and control-protocol behavior;
  use a host-configured grace period when termination starts from an OS signal.
- Maintain one session-wide escalation deadline. Repeated or concurrent triggers
  must not create timer threads, restart the sequence, or extend its deadline.
- Deliver graceful termination to the complete owned process set, escalate to
  forced termination after the deadline, reap every owned process, flush the
  journal, and only then exit the host.
- Receive host signals without executing locks, allocation, journal writes, or
  process traversal in an asynchronous signal handler. Restore the intended
  signal mask and dispositions in the PTY child before `exec`.
- Keep control connections anonymous and multi-purpose: do not classify or
  reserve a connection for termination before decoding each frame.
- Remove the one-second `active_connections` pseudo-drain. Host exit may close
  outstanding control responses after final journal flush; the journal remains
  authoritative.
- Let a recovered AgentD address the exact host incarnation before sending the
  manual signal; never rely on an unverified, potentially recycled PID.
- Cover normal protocol termination, host-directed `SIGTERM`, mixed and repeated
  triggers, an unavailable or blocked control path, PID reuse protection, one
  escalation deadline, complete process reaping, and use of the journal's final
  durable barrier.

## Boundary

This task owns termination entry points and coordination. Addressed `SIGNAL`
requests and process-list semantics remain in
`../process-control-and-pty-closure/TASK.md`; Linux cgroup, pidfd, and descendant
delivery mechanics remain in `../linux-process-tree-control/TASK.md`; the
journal durability API and final sync guarantee remain in
`../journal-durability/TASK.md`.

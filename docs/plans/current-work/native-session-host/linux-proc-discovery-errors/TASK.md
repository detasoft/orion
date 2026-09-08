# Handle Linux Proc Discovery Access Failures

Status: todo
Related: ../linux-process-tree-control/TASK.md
Evidence: ../../../../../session-host/src/platform/unix.rs

Static inspection found that `linux_processes` probes each discovered process's
file descriptors and `linux_process_holds_pty` propagates errors other than
`NotFound`. An access failure for an unrelated `/proc/<pid>/fd` can therefore
abort tracker initialization or the host's main process-observation loop. This
has not yet been reproduced on Linux.

## Scope

- Reproduce the access-failure path on Linux with an unprivileged host and
  record the affected filesystem operation, error, and host behavior.
- Distinguish inaccessible unrelated processes and processes disappearing
  during discovery from failures that prevent observing owned processes.
  Keep unrelated access failures from aborting session startup or service.
- Preserve ownership and PID-identity checks. Do not turn an incomplete scan
  into an empty-tree observation, silently forget owned processes, or signal
  a target whose identity cannot be verified.
- Define explicit diagnostics and behavior for an unreadable owned process
  or unavailable `/proc`, using the existing process tracker and error paths.
- Keep this fix local to discovery. Per-session cgroup ownership, pidfd
  lifecycle observation, and removal of broad scans remain in the linked
  Linux process-tree hardening task.

## Acceptance

- Cover normal root/descendant discovery, reaping, and explicit termination.
- Verify an inaccessible unrelated process does not prevent startup, control
  requests, or continued process observation by an unprivileged Linux host.
- Cover disappearance during stat/fd traversal, access failure for an owned
  process, and a failed scan during termination or finalization. An incomplete
  scan must not falsely establish process-tree completion.
- Use deterministic error injection where needed and record a real Linux
  reproduction and verification result; macOS tests alone do not close this task.

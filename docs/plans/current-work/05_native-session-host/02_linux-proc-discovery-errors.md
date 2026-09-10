# Handle Linux Proc Discovery Access Failures

Status: todo
Related: 01_linux-process-tree-control.md
Evidence: ../../../../session-host/src/platform/linux_process_tree.rs

The implementation integrated in `c0a764d1` ignores inaccessible unrelated
processes during broad `/proc` discovery and preserves errors when a known owned
process cannot be inspected. This behavior has deterministic coverage, but the
original unprivileged Linux failure and the resulting diagnostics have not yet
been reproduced on a real host.

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

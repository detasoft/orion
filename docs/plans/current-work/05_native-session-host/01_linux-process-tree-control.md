# Harden Linux Process-Tree Control

Status: todo
Depends on: completed Unix process host

Turn the implemented Linux subreaper and `/proc` descendant tracking into the
production process-lifecycle boundary. macOS remains a development-only,
best-effort PTY host and is outside this task.

- Owner: codex, session native-process-control-47c2, started 2026-09-04 01:41 Europe/Amsterdam.

## Scope

- Verify that `PR_SET_CHILD_SUBREAPER` is active before the PTY child can fork
  and that all adopted descendants are reaped.
- Use a per-session cgroup v2 and `cgroup.kill` when cgroup delegation is
  available; define the explicit fallback when it is unavailable.
- Prefer pidfd/cgroup lifecycle observation over frequent system-wide `/proc`
  and file-descriptor scans.
- Cover double-fork, `setsid`, closed PTY descriptors, foreground job groups,
  PID reuse, and descendants that fork concurrently with `TERMINATE`.
- Verify graceful termination followed by forced termination until the cgroup
  or tracked descendant set is empty.

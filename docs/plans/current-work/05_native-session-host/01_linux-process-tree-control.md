# Verify Linux Process-Tree Control on Delegated Cgroup v2

Status: paused (a modern delegated cgroup v2 validation host is unavailable)
Depends on: completed Linux process-control implementation `c0a764d1`,
an available Linux 5.14+ host with writable delegated cgroup v2 and `cgroup.kill`

The Linux process-lifecycle implementation is integrated in `c0a764d1`.
It verifies subreaper mode before child release, uses retained pidfds for safe
signal delivery, attempts per-session cgroup v2 ownership, and records a durable
`HOST_WARNING` before falling back to pidfd/subreaper discovery.

The available Linux 5.4.72 host passed 156 unit tests and 26 real process tests,
including fallback ownership, double-fork/`setsid`, PID reuse, late descendants,
foreground signalling, and termination during blocked PTY input. Deterministic
tests cover cgroup setup, rollback, population, cleanup, and `cgroup.kill`.
See [the reconciliation record](../../2026-09-10-linux-process-control-reconciliation.md).

## Remaining acceptance

- Run the real delegated-cgroup lifecycle test on a supporting kernel where the
  host can create a child cgroup and use `cgroup.kill`; the current host takes
  the explicitly tested capability fallback because kernel 5.4 lacks it.
- Verify that the child enters the per-session cgroup before exec, forced
  termination empties the cgroup, adopted children are reaped, and the empty
  session cgroup is removed.
- Record the host/kernel/delegation evidence. Make no production change unless
  the real run exposes a reproducible defect.

## Preserved source

Branch `codex/linux-process-tree-control-47c2` and worktree
`.worktrees/linux-process-tree-control-47c2` retain the earlier design at
`79386060`. LIST_PROCESSES reconciled its useful design in `10e92141`; keep the
branch and worktree until the PTY_CLOSED leaf has reconciled the remaining
design. Its intent ledger, host-owned grace timer, repeated signalling, and old
operation framing are obsolete.

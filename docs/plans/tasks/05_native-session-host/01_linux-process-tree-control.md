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

## Remaining acceptance

- Run the real delegated-cgroup lifecycle test on a supporting kernel where the
  host can create a child cgroup and use `cgroup.kill`; the current host takes
  the explicitly tested capability fallback because kernel 5.4 lacks it.
- Verify that the child enters the per-session cgroup before exec, forced
  termination empties the cgroup, adopted children are reaped, and the empty
  session cgroup is removed.
- Record the host/kernel/delegation evidence. Make no production change unless
  the real run exposes a reproducible defect.

---

## Linux process-control reconciliation

The Linux ownership implementation is reconciled with the current source-aware
control path. The host verifies subreaper mode before child release, attempts
per-session cgroup v2 ownership, and retains pidfds for signal delivery. A
durable `HOST_WARNING` identifies fallback before exec. Cgroup startup failures
must clean up or abort the held child; an uncertain partial attachment cannot
silently select fallback.

The cgroup path observes population and enumerates only its session subtree.
Fallback discovery still scans `/proc` on controls and after root exit. It
validates a candidate again after opening its pidfd. Root-session discovery is
valid only while the original root identity is tracked; adopted descendants
remain discoverable after the root exits. Unreadable known processes retain
their handles and report errors. Missing unrelated process metadata supplies
no ownership evidence. The separate proc-access task still owns reproduction
and broader diagnostics for an unprivileged host.

Every explicit termination is one effect. Graceful termination signals the
observed set once; forced termination writes `cgroup.kill` once when available,
otherwise signals the observed fallback set once. Refresh and liveness checks
never repeat signals. The caller owns timing, further attempts, and escalation.
The host waits for an empty ownership boundary and reaps all adopted children
before finalization. A process forked after fallback delivery requires another
explicit request.

PTY descriptors are nonblocking. INPUT uses readiness polling and reports
closure or failed writes through its existing result path; the reader waits
for readiness and continues draining after journal append failures. This
avoids a reproduced Linux 5.4 blocked write surviving the last slave's exit.
Admission, exact source envelopes, result-only journaling, external signal
forwarding, and caller-owned termination timing remain unchanged.

### Verification boundaries

The existing Linux verification host runs kernel 5.4.72. It exercises pidfd
fallback, double-fork/setsid with closed PTY descriptors, late forks followed
by explicit force, foreground control, source-aware admission, and blocked
INPUT termination. It cannot exercise real `cgroup.kill`; capability/setup,
rollback, kill, and population/reaping cases use deterministic backend tests.
Real delegated cgroup validation on a supporting kernel remains outstanding.

`make session-host-linux-test` packages only native source and shared fixtures
and runs Cargo on the configured SSH host. `SESSION_HOST_LINUX_TOOLCHAIN_BIN`,
`SESSION_HOST_LINUX_CC`, and `SESSION_HOST_LINUX_AR` select existing remote
tools; this goal does not install or change toolchain ownership. The default
remote run/cache parent is `/root/orion-session-host-linux`.

### Reconciled follow-up design

Source commit `79386060248ea965468fb013f014bef5e32c0d09` contained the original
`2026-09-04-native-process-control-design.md` and implementation notes. Those
files are historical source, not current control requirements. In particular,
their intent ledger, grace timer, repeated signalling, and old operation
framing must not be restored.

LIST_PROCESSES and addressed signalling later integrated in `10e92141`, using
the reconciled process-token and retained-identity design. PTY closure later
integrated in `dcebb944` with the following retained properties:

- PTY_CLOSED is emitted exactly once by the terminal reader after its final
  output. Output and terminal controls share the existing serialization
  boundary; later input/resize fail through ordinary command results. Closing
  the PTY does not close process controls while owned processes remain.
- Preserve unknown-event compatibility and add shared protocol fixtures for
  new forms without changing frozen fixtures. No persisted lifecycle state or
  second control execution path is needed.

No process-list request, addressed-signal framing, or PTY_CLOSED event was
implemented by this reconciliation checkpoint. After the follow-up integrations
reconciled the useful design, the source branch and worktree were removed.

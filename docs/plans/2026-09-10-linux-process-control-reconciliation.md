# Linux process-control reconciliation

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

## Verification boundaries

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

## Preserved follow-up design

Source commit `79386060248ea965468fb013f014bef5e32c0d09`, in the preserved
`codex/linux-process-tree-control-47c2` branch, contains the original
`2026-09-04-native-process-control-design.md` and implementation notes. Those
files are historical source, not current control requirements. In particular,
their intent ledger, grace timer, repeated signalling, and old operation
framing must not be restored.

The useful remaining design is:

- LIST_PROCESSES is a read-only, non-journaled snapshot of owned processes.
  Entries expose an incarnation-local, nonzero, never-reused process token,
  diagnostic OS PID, and original-root flag. Dead tokens are retired.
- Addressed SIGNAL resolves a token through the existing owner to a retained
  kernel identity. Unknown/stale tokens never fall back to numeric PID. It
  must use current source-aware admission and result semantics. Foreground
  signalling retains its terminal role; TERMINATE addresses the whole tree.
- PTY_CLOSED is emitted exactly once by the terminal reader after its final
  output. Output and terminal controls share the existing serialization
  boundary; later input/resize fail through ordinary command results. Closing
  the PTY does not close process controls while owned processes remain.
- Preserve unknown-event compatibility and add shared protocol fixtures for
  new forms without changing frozen fixtures. No persisted lifecycle state or
  second control execution path is needed.

These remain separate task leaves. No process-list request, addressed-signal
framing, or PTY_CLOSED event is implemented by this checkpoint. Keep the source
branch/worktree until those leaves have reconciled their detailed designs.

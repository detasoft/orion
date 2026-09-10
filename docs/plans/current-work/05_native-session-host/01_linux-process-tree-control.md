# Harden Linux Process-Tree Control

Status: active
Depends on: completed Unix process host

Turn the implemented Linux subreaper and `/proc` descendant tracking into the
production process-lifecycle boundary. macOS remains a development-only,
best-effort PTY host and is outside this task.

- Owner: codex, session 01a08b4d-b58e-7142-b7ca-449bf4d815db,
  branch `codex/linux-process-control-reconcile-b58e`,
  worktree `.worktrees/linux-process-control-reconcile-b58e`, started 2026-09-10 14:44 Europe/Amsterdam.
- User authorized takeover from `native-process-control-47c2` and integration on 2026-09-10.
- Next: reconcile the preserved branch with current main and native-control contracts,
  resolve overlaps, assess remaining acceptance, and verify the retained implementation.
  Preserved source: branch `codex/linux-process-tree-control-47c2`,
  worktree `.worktrees/linux-process-tree-control-47c2`.
  Saved HEAD: `79386060248ea965468fb013f014bef5e32c0d09`;
  base: `670fce16db491165d88a53f323ba9976e4defc29`. Nine unique commits contain
  Linux pidfd/cgroup ownership, termination hardening, HOST_WARNING, and tests.
  The saved worktree was clean when paused; no new verification was run for this handoff.
  Preserve this work until its progress has been reconciled. Process listing,
  addressed-signal requests, and PTY_CLOSED are designed there but not implemented.

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

## Reconciliation boundaries

- The current native-control contract in
  `../../2026-09-03-native-control-journal-idempotency-design.md` governs integration.
  Preserve effect-once admission, result-only journaling, and caller-owned termination
  timing and escalation; do not restore the old intent ledger or host grace timer.
- Retain the Linux ownership implementation and meaningful regression coverage from
  the preserved source after review against current main. Update affected consumers
  and protocol documentation together where retained HOST_WARNING requires them.
- LIST_PROCESSES, addressed-signal framing, and PTY_CLOSED remain separate leaves.
  Preserve their source design for subsequent reconciliation without importing its
  obsolete control semantics as current requirements.

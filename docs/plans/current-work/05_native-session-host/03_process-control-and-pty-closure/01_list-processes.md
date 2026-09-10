# Implement LIST_PROCESSES and Addressed Signals

Status: paused
Parent: TASK.md
Contract: ../../../2026-09-03-native-control-journal-idempotency-design.md
Related: ../01_linux-process-tree-control.md
Next: after the parent dependency is satisfied, compare the process-control design
in branch `codex/linux-process-tree-control-47c2`, worktree
`.worktrees/linux-process-tree-control-47c2`, with the retained Linux implementation
and current control contracts, then implement listing and addressed signals.
The preserved branch at `79386060` contains the design but no listing request implementation.

Expose a current snapshot of processes owned by a live session and allow a
control client to signal a listed process safely.

## Scope

- Add read-only `LIST_PROCESSES` request/response framing with explicit payload
  bounds and versioning. Reuse the existing process tracker; listing must not
  advance the operation admission high-water mark or append command results.
- Return a session-scoped process identity usable by addressed `SIGNAL`.
  Validate ownership and identity at delivery so a stale or recycled PID cannot
  target an unrelated process. Coordinate Linux identity mechanics with the
  existing process-tree hardening task and document macOS limits explicitly.
- Route addressed signals through existing operation admission and result
  recording. Preserve foreground signalling and whole-tree `TERMINATE`.
- Update Rust handlers, Java control consumers, protocol documentation, and
  shared fixtures together. Replace affected internal APIs directly.

## Acceptance

- List a live root and descendants; observe root exit while descendants remain
  and refresh the snapshot after process exit.
- Deliver an addressed signal to an owned process and reject foreign, exited,
  or recycled identities without signalling an unrelated process.
- Cover concurrent listing, process exit, and termination with a real host;
  preserve session availability while any owned process remains.

## Boundary

This task owns the common listing and addressed-signal contract. Linux
pidfd/cgroup implementation remains in the linked hardening task. PTY closure,
harness ingress, and a control-request queue are separate work.

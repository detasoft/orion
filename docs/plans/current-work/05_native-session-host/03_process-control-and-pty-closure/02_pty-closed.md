# Implement PTY_CLOSED and Terminal Availability

Status: todo
Parent: TASK.md
Contract: ../../../2026-09-03-native-control-journal-idempotency-design.md
Related: completed LIST_PROCESSES and addressed signalling `10e92141`
- Owner: codex, session pty-closed-c214, branch `codex/pty-closed-c214`,
  worktree `.worktrees/pty-closed-c214`, started 2026-09-10 17:54 Europe/Amsterdam.
Next: compare the PTY-closure design in branch
`codex/linux-process-tree-control-47c2`, worktree
`.worktrees/linux-process-tree-control-47c2`, with current journal and control
contracts, resolve overlaps, and implement terminal closure independently of process liveness.
The preserved branch at `79386060` contains the design but no PTY_CLOSED event implementation.

Expose terminal closure as a journal fact while keeping process-tree liveness
and control admission in their existing owners.

## Scope

- Allocate and document an empty `PTY_CLOSED` event. With a writable journal,
  append it exactly once after the final `PTY_OUTPUT`; emit no later output.
  Preserve current append-failure reporting and continued process service.
- Keep PTY availability as local resource state. After closure, admitted
  `INPUT` and `RESIZE` produce the ordinary failed `COMMAND_RESULT` when its
  append succeeds, without attempting terminal effects.
- Keep status, process listing, signals, termination, and any implemented
  harness ingress available while owned processes remain. PTY closure and
  root exit alone must not trigger termination or cancel unrelated operations.
- Preserve finalization after the last owned process is reaped. Add no
  persisted lifecycle replica or combined terminal/process state machine.
- Update the journal encoder, existing Java readers/projections, protocol
  documentation, and shared fixtures together.

## Acceptance

- Cover normal terminal exit and root exit with a PTY-holding descendant;
  verify one closure event ordered after all terminal output.
- Close the PTY while detached owned processes remain. Verify failed input and
  resize results, working process controls, and final host exit after reaping.
- Cover closure racing with input/resize, journal append failure, and blocked
  input with `TERMINATE` arriving on another connection.

## Boundary

Process discovery and safe process identity belong to their existing tasks.
This task does not implement harness ingress, shutdown timing, or escalation.

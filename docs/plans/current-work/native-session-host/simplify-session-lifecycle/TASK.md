# Simplify Session Lifecycle Ownership

Status: todo
Depends on:
[process control and PTY closure](../process-control-and-pty-closure/TASK.md),
[explicit session termination](../termination-coordination/TASK.md)

Resolve overlapping lifecycle decisions without introducing a global state
machine for combinations of PTY availability, root exit, and descendant
liveness.

## Scope

- Keep PTY availability as local resource state. Publish exactly one
  `PTY_CLOSED` after the final `PTY_OUTPUT`; subsequent `INPUT` and `RESIZE`
  return the ordinary terminal-unavailable result.
- Neither PTY closure nor root-process exit alone starts termination or cancels
  unrelated operations. Continue process listing, addressed `SIGNAL`,
  `TERMINATE`, and harness ingress while owned processes remain.
- Give each fact one authoritative owner: the PTY owner reports availability,
  the process owner tracks remaining owned processes, and explicit control
  operations perform their requested effects.
- Keep sequence admission and active-operation accounting separate from
  session lifecycle. The high-water mark rejects replay; it is not a result
  ledger and does not retain pending effects.
- Remove independently mutable lifecycle flags where they duplicate an
  authoritative fact, while retaining distinct state needed to describe an
  effect already performed.

## Acceptance

- Cover normal `INPUT` and shutdown, root exit with a live PTY-holding
  descendant, and PTY closure with live descendants.
- In the PTY-closure case, verify one ordered `PTY_CLOSED`, an
  unavailable-input result, and working process controls.
- Cover a blocked `INPUT` together with `TERMINATE` from another connection.
- Document the remaining state owners briefly; do not add persisted lifecycle
  state or a cross-product of terminal and process states.

## Boundary

This is a lifecycle ownership simplification, not a second implementation of
the prerequisite tasks. Their PTY protocol and process-control contracts
remain authoritative; Linux process discovery and PID-identity mechanics
remain in `../linux-process-tree-control/TASK.md`.

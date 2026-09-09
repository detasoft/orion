# Keep Native Session Shutdown Simple

Status: active
Depends on:
[explicit session termination](04_termination-coordination.md),
[process control and PTY closure](03_process-control-and-pty-closure/TASK.md)

Align shutdown behavior with the session-host boundary. The host is a process
proxy: it admits operations by `operationSequence`, executes each admitted
effect once, records its result, and reports journal failures on `stderr`.

## Scope

- Keep operation state limited to the in-memory sequence high-water mark and
  active-operation accounting needed for finalization. Neither state tracks
  an unjournaled result or a second lifecycle model.
- Ensure `TERMINATE` can signal the owned process tree while another
  connection has a blocked `INPUT`. Do not make termination depend on the
  ordinary effect-order mutex.
- Preserve one `COMMAND_RESULT` per admitted operation when the journal is
  writable. A failed effect may have produced a partial side effect; a failed
  result append is reported on `stderr` and does not trigger effect replay.
- Keep graceful and force termination as explicit operations. The server owns
  grace periods and escalation by sending another operation; the host does not
  schedule or retry either one.
- Keep process discovery, PID-identity checks, PTY closure, and final journal
  synchronization in their existing owners.

## Acceptance

- Stale or repeated sequences are rejected without executing the effect again.
- A blocked `INPUT` does not prevent a `TERMINATE` received on another control
  connection from reaching the process tree.
- Successful effects and effect failures produce their corresponding
  `COMMAND_RESULT` records. Missing result records are treated as unknown,
  not as permission to replay the effect.
- Finalization admits no new operations and waits for admitted handlers before
  the final journal flush.
- Tests cover partial input, termination during blocked input, process-tree
  cleanup, and interleaved result records identified by operation sequence.

## Boundary

This task coordinates lifecycle simplification and the interaction between
operation admission and explicit termination. Platform process discovery and
PID-identity mechanics remain in
`01_linux-process-tree-control.md`; the protocol and `PTY_CLOSED` contract
remain in `03_process-control-and-pty-closure/TASK.md`.

# Replace Per-I/O Timeout Scheduling

Status: todo
Depends on: ../phase-aware-transport-exchange/TASK.md

Enforce transport inactivity without allocating and cancelling a scheduled
task for every primitive byte read, write, or flush.

## Candidate Models

- Prefer transport-native connect and read deadlines where they faithfully
  implement the promised semantics.
- Prefer one session-level inactivity deadline over separate public read and
  write timeout values: any successful read or write updates progress, and one
  shared-scheduler watchdog closes a session whose deadline has expired.
- Consider a watchdog per explicit exchange phase only if session-level progress
  cannot distinguish request and response stalls without extra state.

Compare these models against the current contract before implementation. The
expected target is connect timeout, one bidirectional inactivity timeout, and
one whole-operation deadline. Retain distinct read and write timeout settings
only if a concrete transport or synchronization scenario requires callers to
configure them differently. Do not create a thread or scheduled task per
`readUnsignedByte`, `readCopy`, `readInto`, `write`, or `flush`.

## Scope

- Keep the executor's whole-operation deadline separate from transport
  connect and inactivity deadlines.
- Replace `GitClientOptions.readTimeout` and `writeTimeout` with one inactivity
  value if the comparison finds no required asymmetric policy; make the API
  change directly without deprecated accessors.
- Ensure timeout cancellation closes the underlying socket, SSH channel, or
  HTTP exchange and unblocks the operation.
- Remove overlapping watchdogs where a transport-native deadline already owns
  the same guarantee.
- Replace the current timing path directly; do not keep the per-call wrapper as
  a fallback compatibility path.

## Completion Criteria

- Instrumented tests prove pkt-line header fragmentation does not schedule one
  timer per byte or primitive I/O call.
- Smart HTTP and SSH tests both cover stalled advertisement read, stalled pack
  read, stalled request write, and whole-operation timeout.
- Tests prove whether read and write activity share one deadline; if the final
  contract retains separate values, document the concrete asymmetric case that
  requires them.
- On both transports, a transfer that makes progress before each inactivity
  deadline may run longer than one timeout interval and still succeed.
- Tests cover timeout-driven close, cancellation racing with open, a late
  watchdog after successful completion, and idempotent resource cleanup.
- TCP tests document which read timeout is transport-native and verify that no
  duplicate watchdog enforces the same deadline.

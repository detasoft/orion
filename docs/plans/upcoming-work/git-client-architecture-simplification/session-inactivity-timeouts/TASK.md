# Use One Session Inactivity Timeout

Status: todo
Depends on: ../phase-aware-transport-exchange/TASK.md

Replace separate read and write timeouts and per-call scheduled tasks with one
inactivity timeout for the complete transport session or exchange.

## Timeout Model

- `connectTimeout` bounds transport connection and authentication setup.
- `inactivityTimeout` bounds absence of successful read or write progress after
  the exchange opens.
- `operationTimeout` bounds the complete client operation, including planning,
  negotiation, local callbacks, transfer, status, and close.

Maintain at most one active inactivity watchdog per session or exchange. Any
successful transport read or write refreshes its progress deadline; expiry
closes the underlying transport and unblocks the operation. A transport-native
deadline may implement the same guarantee where it is exact, but must not be
layered with a duplicate scheduler deadline for the same stall.

## Scope

- Keep the executor's whole-operation deadline separate from transport
  connect and inactivity deadlines.
- Replace `GitClientOptions.readTimeout` and `writeTimeout` directly with one
  `inactivityTimeout`, without deprecated accessors or compatibility options.
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
- Tests prove that both read and write progress refresh the same inactivity
  deadline.
- On both transports, a transfer that makes progress before each inactivity
  deadline may run longer than one timeout interval and still succeed.
- Tests cover timeout-driven close, cancellation racing with open, a late
  watchdog after successful completion, and idempotent resource cleanup.
- TCP tests document which part of inactivity enforcement is transport-native
  and verify that no duplicate watchdog enforces the same deadline.

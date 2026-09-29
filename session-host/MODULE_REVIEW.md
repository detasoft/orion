# Module Review: `session-host`

## 1. Detached connection workers form an unbounded pending-operation queue

**Problem.** Idle clients retain detached native threads indefinitely. When PTY input blocks, other connections
receive admission receipts and accumulate workers behind the ordinary-effect mutex. Disconnect does not cancel
admitted effects.

**Sources.** [Accept loop](src/platform/unix.rs#L1035),
[frame reads](src/platform/unix.rs#L1074),
[admission before effects](src/platform/unix.rs#L1272), and
[PTY input readiness](src/platform/unix.rs#L1428).
[Blocked input](tests/unix_process_host.rs#L1589) and
[termination coverage](tests/unix_process_host.rs#L1726) show independent admission and required termination
bypass. AgentD's
[persistent terminal connection](../agentd/src/main/java/pro/deta/orion/agentd/terminal/LocalTerminalAttacher.java#L330)
bounds that lane's connection use, while other clients remain unbounded.

**Documented behavior.** The [protocol](protocol/README.md#L244) separates admission from effects.
The [queue investigation](../docs/plans/tasks/05_native-session-host/12_control-request-queue.md) is deferred.

**Contract.** Preserve admission receipts, uncertainty after disconnect, and termination during blocked input.
Maximum clients, pending effects, and overload outcomes remain unspecified and require a decision.

**Minimal repair.** Define those bounds, then constrain connection/admission resources in the existing blocking
implementation while reserving termination capacity. Cover idle clients, overload, blocked input, and
finalization.

**Alternatives and consequences.** A global connection cap can block termination. A pool or async runtime does
not independently bound admitted effects. Rejecting excess clients introduces an overload contract.

**Confidence.** High in resource growth; production exhaustion frequency and appropriate capacity are unknown.

**Priority signals.** Importance: medium, potentially high under sustained blocked input. Repair ease: medium
to low because capacity and termination availability must be designed together.

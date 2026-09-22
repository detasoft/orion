# Module Review: `session-host`

### 1. Detached connection workers form an unbounded pending-operation queue

**Problem.** If a child stops consuming PTY input, commands arriving on separate connections can still receive
`RECEIVED` while their workers accumulate behind the blocked effect. Closing a socket does not cancel its
admitted operation. Idle clients also retain detached workers without a read deadline or connection bound.

**Sources.** [`spawn_accept_loop`](src/platform/unix.rs#L1029) creates a detached thread per socket;
[`serve_connection`](src/platform/unix.rs#L1081) reads the next frame without a read deadline.
[`handle_operation`](src/platform/unix.rs#L1290) acknowledges before acquiring the ordinary-effect mutex;
[PTY writes](src/platform/unix.rs#L1441) can remain blocked. The
[blocked-input test](tests/unix_process_host.rs#L1567) demonstrates independent admission on another connection
but does not bound accumulated workers or operations.

**Documented behavior.** The [native protocol](protocol/README.md#L254) intentionally separates admission from
effects and lets `TERMINATE` bypass the effect mutex. The
[queue investigation](../docs/plans/tasks/05_native-session-host/12_control-request-queue.md) is explicitly
deferred and does not establish a client count or authorize a particular queue.

**Contract.** Preserve nonblocking admission where capacity exists, execution uncertainty after disconnect,
and a usable termination path during blocked input. Maximum clients, pending effects, and overload behavior
remain unspecified and require a decision before repair.

**Minimal repair.** Establish required concurrency, then bound connection/admission resources using the existing
blocking implementation. Prevent ordinary blocked work from consuming all termination capacity. Cover overload,
idle clients, blocked input, and finalization; a new pool or async runtime is not justified by present evidence.

**Alternatives and consequences.** AgentD's terminal lane uses one persistent connection, which preserves its
operation order and prevents that lane from accumulating connection workers, but does not bound other clients. A naive connection cap can itself block termination.
Rejecting excess work changes overload behavior, which must be explicit.

**Confidence.** High in the mechanism and native blocked-input test. The required capacity and production exhaustion
frequency are unknown; no stress reproduction was run.

**Priority signals.** Importance: medium, with potentially high impact under sustained blocked input.
Repair ease: medium to low because resource bounds and termination availability must be designed together.

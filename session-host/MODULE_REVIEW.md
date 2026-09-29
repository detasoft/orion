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

## 5. The exact Rust toolchain pin again has two owners

**Problem.** `rust-install` hardcodes `1.97.0` independently from `rust-toolchain.toml`. Updating the documented
pin alone makes bootstrap check/install another version. Current values match; the issue is duplicate ownership.

**Sources.** [Bootstrap literals and callers](../Makefile#L103),
[canonical pin](rust-toolchain.toml#L2), [Maven plugin](pom.xml#L43), and
[Cargo compatibility floor](Cargo.toml#L5).

**Documented behavior.** [README](README.md#L55) assigns the exact build pin to `rust-toolchain.toml` and
distinguishes Cargo's compatibility floor.

**Contract.** Preserve direct Cargo builds and automatic bootstrap of the exact repository pin. Keep
`rust-version` as a distinct compatibility constraint.

**Minimal repair.** Have bootstrap use the existing Rustup/Cargo pin, deriving any required install argument
from that owner. Validate the actual Make and Cargo entry points.

**Alternatives and consequences.** Making Make authoritative restructures direct Cargo selection and
contradicts current documentation. Manual synchronization retains unnecessary upgrade coupling. No runtime
or protocol change is needed.

**Confidence.** High; both literals and their consumers are present. No bootstrap-pin behavioral test was found.

**Priority signals.** Importance: low. Repair ease: high, limited to build-bootstrap ownership.

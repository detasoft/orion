# Module Review: `agent-protocol`

### 10. The shared journal specification promises an unconditional durable start outcome

**Problem.** The shared specification says exactly one start outcome is durable before the host leaves startup.
On a `PROCESS_STARTED` append failure after exec, the current native host instead logs the failure and publishes
the live session. A consumer following the shared guarantee could wrongly interpret a missing start record as
proof that no process was launched.

**Sources.** The [unconditional guarantee](protocol/README.md#L170) contradicts
[`PendingStartOutcome.started`](../session-host/src/platform/unix.rs#L322) and the
[native start-outcome contract](../session-host/protocol/README.md#L147). AgentD's
[`NativeRuntime.awaitHandoff`](../agentd/src/main/java/pro/deta/orion/agentd/runtime/NativeRuntime.java#L203)
uses manifest, journal readability, and live STATUS rather than requiring `PROCESS_STARTED`.

**Documented behavior.** The native specification explicitly permits a missing durable outcome after append
failure, keeps the child alive, and forbids treating a post-exec failure as `SESSION_START_FAILED`.
The shared specification asserts a stronger, incompatible guarantee.

**Contract.** Preserve the accepted native failure semantics and recovery uncertainty. Missing durable history
does not prove absence of execution; this finding does not request a new shutdown or recovery policy.

**Minimal repair.** Replace the unconditional statement with the implemented attempt-and-failure semantics and
refer to the authoritative native contract. No production change or new concept is needed.

**Alternatives and consequences.** Enforcing the stronger promise would require changing post-exec lifecycle and
storage-failure policy, not merely documentation. No current consumer requiring that stronger promise was found.

**Confidence.** High on the contradiction; actual misinterpretation by a deployed consumer was not established.

**Priority signals.** Importance: medium for a shared recovery contract. Repair ease: high, documentation only.

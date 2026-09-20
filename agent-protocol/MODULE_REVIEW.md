# Module Review: `agent-protocol`

### 8. Invalid typed PTY fields escape the protocol error boundary

**Problem.** A complete CBOR record with an empty `ptyInputId` or zero terminal dimension passes opaque record
decoding, then `decodeKnownPayload` throws `IllegalArgumentException` from a payload constructor. During initial
terminal inspection this escapes to `main` instead of producing the normal bounded journal-failure result.
Concrete records are `8301190101826040` (empty input identity) and `830119010282001818` (zero columns, 24 rows).

**Sources.** [`decodePtyInput` and `decodePtyResize`](src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java#L238)
directly invoke [validated payload constructors](src/main/java/pro/deta/orion/agent/protocol/SessionEventPayload.java#L64).
Neighboring [`decodeHostWarning`](src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java#L226)
already translates constructor validation into `AgentProtocolException(INVALID_FIELD)`.
[`TerminalJournalFollower.inspect`](../agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalJournalFollower.java#L44)
catches only the protocol exception. Its
[initial caller](../agentd/src/main/java/pro/deta/orion/agentd/terminal/LocalTerminalAttacher.java#L73) and
[terminal entry point](../agentd/src/main/java/pro/deta/orion/agentd/AgentdMain.java#L59) do not contain this unchecked
failure. [`SessionEventCodecTest`](src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java#L485)
covers wrong shapes/types, but misses valid CBOR types with invalid field values.

**Documented behavior.** [Terminal failure handling](../docs/plans/tasks/04_agentd/07_local-terminal/TASK.md#L37)
requires bounded session-local diagnostics for corrupt journal data. No distinct unchecked failure policy for
known payload fields was found.

**Contract.** Preserve opaque byte forwarding, current field validity, and constructor validation for direct
Java callers. Invalid untrusted typed payloads must reach the existing protocol-error handling boundary.

**Minimal repair.** Translate constructor validation in the existing typed decoding path, following neighboring
decoders. Cover invalid input identities, zero dimensions, and initial terminal preflight behavior without
adding an error model or weakening validation.

**Alternatives and consequences.** Catching every runtime exception in the terminal only patches one consumer.
Validating all known payloads during opaque decoding changes forwarding and persistence semantics unnecessarily.
The local translation needs no wire or storage migration.

**Confidence.** High from the explicit call chain. Native writers normally produce valid values; corrupted data
or another producer is required, and no runtime reproduction was run.

**Priority signals.** Importance: medium, an actual terminal path bypasses its failure interface.
Repair ease: high, a local translation plus protocol and consumer regressions.

### 9. Start failures have two encoding paths with different validation

**Problem.** The typed `encode` branch and public `encodeStartFailure` both serialize `SESSION_START_FAILED`.
The relay still calls the specialized writer. Their validation already differs: the specialized method rejects
negative Java `long` omitted counts, while the typed path represents the wire's unsigned value.

**Sources.** The [typed branch](src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java#L74),
[specialized writer](src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java#L108), and
[typed payload](src/main/java/pro/deta/orion/agent/protocol/SessionEventPayload.java#L90) duplicate ownership.
[`SessionJournalRelay.registerStartFailure`](../agentd/src/main/java/pro/deta/orion/agentd/journal/SessionJournalRelay.java#L97)
is the production specialized caller; its current omitted counts fit both paths. Existing
[codec coverage](src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java#L327) and
[relay coverage](../agentd/src/test/java/pro/deta/orion/agentd/session/SessionJournalRelayTest.java#L59)
exercise this path, while [server confirmation tests](../agent-session-server/src/test/java/pro/deta/orion/agent/server/auth/SessionCommandServiceTest.java#L288)
use the typed writer.

**Documented behavior.** The [event contract](protocol/README.md#L140) defines one representation, with diagnostic
bounds and omitted-byte semantics in the [native protocol](../session-host/protocol/README.md#L147).
No requirement for two Java producer APIs was found.

**Contract.** Preserve version-one bytes, diagnostic bounds, relay truncation/counts, and retransmission until
durable acknowledgement. There is no established live byte mismatch for the relay's current inputs.

**Minimal repair.** Move all in-repository callers and meaningful tests to `encode` with the existing
`SessionStartFailed` payload, then delete `encodeStartFailure`. Preserve wire assertions and relay behavior
coverage through the canonical path.

**Alternatives and consequences.** A forwarding alias retains an unnecessary internal API; deleting the typed
branch fights the existing typed decoder model. The chosen deletion changes internal call sites but no wire
format, persisted state, or runtime concept.

**Confidence.** High on duplication and the available replacement. External binary consumers were not surveyed;
the inspected production usage is internal to this repository.

**Priority signals.** Importance: low, current relay values work in both paths. Repair ease: high, a small
cross-module caller migration and deletion with existing behavioral tests.

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

# Module Review: `agent-session-server`

## 3. Session registry keeps an unused durable terminal-outcome model

**Problem.** Session records serialize an optional authoritative terminal outcome; reconciliation preserves it,
and `descriptor()` overlays it onto observations. Every call to `recordOutcome` is in tests. Production
commands instead obtain terminal evidence from committed journals. A second lifecycle authority and its
mutation/serialization path therefore have no current production writer.

**Sources.** [Outcome overlay](src/main/java/pro/deta/orion/agent/server/registry/SessionRecord.java#L10),
[reconciliation](src/main/java/pro/deta/orion/agent/server/registry/FileSystemSessionRegistry.java#L109),
[mutation API](src/main/java/pro/deta/orion/agent/server/registry/FileSystemSessionRegistry.java#L136),
[codec](src/main/java/pro/deta/orion/agent/server/registry/SessionRecordCodec.java#L41), and
[actual validation](src/main/java/pro/deta/orion/agent/server/command/SessionCommandService.java#L137).
[Registry tests](src/test/java/pro/deta/orion/agent/server/registry/FileSystemSessionRegistryTest.java#L101) and
[command test](src/test/java/pro/deta/orion/agent/server/auth/SessionCommandServiceTest.java#L232)
supply the otherwise unwritten state.

**Documented behavior.** [Server scope](../docs/plans/tasks/03_agent-session-server/TASK.md) defers semantic
projections. `SessionCommandService` describes the journal as its sole completion authority.

**Contract.** Preserve session ownership, observed metadata, durable journal evidence, and rejection of commands
after terminal events. No current production requirement for a separate outcome writer was found. Existing
registry-file compatibility remains an explicit unknown.

**Minimal repair.** Remove the writer, outcome type, and overlay; use one observed descriptor and journal-backed
terminal validation. Update tests to use reported/journal evidence. Resolve the persisted-format requirement
before removing serialized outcomes; previously stored values cannot be silently discarded.

**Alternatives and consequences.** Wiring replication into the outcome API adds a second durable projection
with recovery/atomicity obligations. A legacy decoder needs a demonstrated compatibility requirement.
Removal changes internal APIs and potentially persisted records, without requiring transport changes.

**Confidence.** High for the absent writer and duplicate concept; deployment compatibility is unknown.

**Priority signals.** Importance: medium, due to unnecessary durable lifecycle state and misleading coverage.
Repair ease: medium, with a persisted-format decision.

## 4. Small command queries materialize complete output histories under a shared monitor

**Problem.** Every input, resize, signal, or terminate reads the complete journal to determine whether a session
exited. Every command-status request materializes the same output history to find one result. These scans occur
under one command-service monitor shared by agents. Ordinary terminal input and per-command polling repeatedly
retain unrelated PTY payloads and delay other commands as history grows.

**Sources.** [Admission](src/main/java/pro/deta/orion/agent/server/command/SessionCommandService.java#L137),
[status](src/main/java/pro/deta/orion/agent/server/command/SessionCommandService.java#L188),
[history retention](src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java#L243), and
[existing selection](src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java#L1066).
[HTTP callers](../net/http-core/src/main/java/pro/deta/orion/transport/http/SessionCommandsRoute.java#L43),
[terminal input](../net/frontend/ui/src/components/SessionTerminal.vue#L67), and
[polling](../net/frontend/ui/src/lib/session-commands.js#L32) are real production paths.
[Command tests](src/test/java/pro/deta/orion/agent/server/auth/SessionCommandServiceTest.java#L188)
cover journal-derived completion and terminal validation.

**Documented behavior and contract.** Completion requires durable journal evidence, and terminal events prevent
new effects. Raw-history APIs must continue returning requested history with snapshot and integrity guarantees.
No requirement needs all unrelated output retained while answering these narrow queries.

**Minimal repair.** Extend existing reading with bounded selection/visitation, retaining needed query results.
Keep interpretation in the command owner and preserve snapshot/integrity checks. Cover large output prefixes,
result correlation, terminal records, compressed segments, and concurrent append.

**Alternatives and consequences.** A durable projection/cache adds cursor and recovery ownership. Filtered
scanning bounds retention while leaving repeated I/O and some global contention. Removing synchronization alone
risks sequence/delivery ordering and does not solve materialization.

**Confidence.** High from complete-history calls and shared locking; no heap or latency measurement was run.

**Priority signals.** Importance: high for growing session history and interactive responsiveness. Repair ease:
medium, by reusing reader machinery with behavior coverage.

## 5. Facade concurrency test pins a private monitor and JVM blocking state

**Problem.** The facade regression test reflects `ROOT_OWNER_MONITOR`, locks it, and requires
`Thread.State.BLOCKED`. Renaming the field or substituting an equivalent lock breaks the test while supported
behavior remains unchanged.

**Sources.** [Reflective test](src/test/java/pro/deta/orion/agent/server/AgentSessionServerTest.java#L59),
[private monitor](src/main/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorage.java#L40),
[storage interface](src/main/java/pro/deta/orion/agent/server/journal/SessionJournalStorage.java#L10), and
[behavioral concurrency tests](src/test/java/pro/deta/orion/agent/server/journal/JournalConcurrencyTest.java#L110).

**Documented behavior.** The [test-quality rule](../.agents/skills/orion-minimal-implementation/SKILL.md#verify-and-review)
prohibits incidental structure assertions.

**Contract.** Preserve regression coverage that blocked reads allow unrelated facade operations, shutdown waits
for admitted reads, and storage ownership becomes reusable. The monitor field and thread state are incidental.

**Minimal repair.** Arrange a blocked read through an existing controlled storage/fault-injection boundary,
or a narrow marked `@TestOnly` hook if existing mechanisms are insufficient. Assert observable operations and
completion instead of reflecting the monitor or requiring a particular JVM state.

**Alternatives and consequences.** Deleting the test loses meaningful historical regression coverage.
A broad injection framework adds unnecessary complexity. Any test seam must stay narrow and marked.

**Confidence.** High from explicit field lookup and blocking-state assertion.

**Priority signals.** Importance: medium as a test-quality violation. Repair ease: medium, because concurrency
coverage must survive replacement of the blocking arrangement.

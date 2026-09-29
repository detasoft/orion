# Module Review: `agent-session-server`

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

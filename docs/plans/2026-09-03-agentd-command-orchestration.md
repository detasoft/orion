# AgentD Command Orchestration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Route server session commands through stateless, bounded per-session orchestration whose durable
outcomes and recovery state come exclusively from session journals.

**Architecture:** Keep the exact inbound server CBOR item beside its typed message, queue commands in one
bounded serial lane per session, and run different session lanes concurrently. Recover each lane from the
server's acknowledged operation prefix plus an independent local journal suffix scan; delegate durable
execution intent/result recording to the prerequisite native host and durable upload/ACK handling to the
prerequisite journal sync service.

**Tech Stack:** Java 25, Maven, JUnit 5, AssertJ, Jetty HTTP/2, CBOR Sequence, existing AgentD runtime,
discovery, local-control, journal-reader, and journal-sync boundaries.

---

## Scope Rules

- Work only in the dedicated command-orchestration worktree until the review gate.
- Follow @superpowers:test-driven-development for every production behavior below.
- Run every Maven command outside the sandbox. Use `make run-test` for focused tests and `make test` after
  each commit, as required by `AGENTS.md`.
- Do not implement journal file reading, HTTP/2 journal pumping, `ACK_JOURNAL`, journal retention, or native
  host operation deduplication in this leaf.
- Do not add `hostInstanceId`; retain and document the approved SessionId/endpoint/PID correlation risk.
- Do not implement secret redaction here. The separate
  `docs/plans/current-work/agentd/diagnostic-secret-redaction/TASK.md` remains required before release.
- Do not implement server journal projection or command completion here. Deriving and sending the acknowledged
  operation prefix belongs to `docs/plans/current-work/agent-session-server/session-replication/TASK.md` with
  journal storage; pending-command completion belongs to
  `docs/plans/current-work/agent-session-server/command-service/TASK.md`.

### Task 0: Satisfy and inspect prerequisite contracts

**Files:**
- Inspect: `docs/plans/current-work/agentd/journal-reader/TASK.md`
- Inspect: `docs/plans/current-work/agentd/journal-sync/TASK.md`
- Inspect: `docs/plans/current-work/native-session-host/control-journal-idempotency/TASK.md`
- Inspect: `agentd/src/main/java/pro/deta/orion/agentd/journal/`
- Inspect: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlCommand.java`
- Inspect: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlResult.java`
- Inspect: `session-host/protocol/README.md`
- Inspect: `session-host/protocol/fixtures/`

**Step 1: Rebase onto the prerequisite-complete main**

Run: `git status --short`

Expected: no output.

Run: `git rebase main`

Expected: the task branch rebases without a merge commit. Stop on any unrelated conflict; do not resolve by
discarding another task's work.

**Step 2: Verify the three prerequisite leaves are integrated**

Run: `test ! -e docs/plans/current-work/agentd/journal-reader/TASK.md`

Run: `test ! -e docs/plans/current-work/agentd/journal-sync/TASK.md`

Run: `test ! -e docs/plans/current-work/native-session-host/control-journal-idempotency/TASK.md`

Expected: all commands exit zero because completed dedicated-worktree leaves are removed during integration.
If any task remains, stop. Do not copy its implementation into this leaf.

**Step 3: Verify the native contract required by orchestration**

Confirm the integrated host contract and fixtures provide all of these:

```text
INPUT, RESIZE, SIGNAL, TERMINATE share operationSequence
request payload retains exact Agent server CBOR envelope
COMMAND_ACCEPTED is durable before the external effect
COMMAND_RESULT is durable before the host response
host response contains the COMMAND_RESULT eventId
unmatched COMMAND_ACCEPTED recovers as COMMAND_RESULT(AMBIGUOUS)
```

Expected: every line is implemented and covered by native fixtures/tests. If `RESIZE` or `TERMINATE` is still
missing, stop and return the prerequisite task to its owner.

Separately verify the integrated journal-sync contract sends non-journaled `ACK_JOURNAL` only for a server
durability acknowledgement and completes only after the host durably applies that retention watermark.

**Step 4: Verify start-outcome coverage**

Inspect the native launch sequence and fixtures. Once a host journal exists, the host must eventually record
either the successful start observation (`PROCESS_STARTED`) or `SESSION_START_FAILED`, including failures after
journal creation but before child start.

Expected: no journal can be left permanently without a start outcome. This is currently an unowned prerequisite
gap; stop and ask the task coordinator to route it into the native-session-host task tree if it remains.

**Step 5: Record actual prerequisite API names before continuing**

The remaining steps use these expected names:

```text
SessionJournalReader.readAfter(...)
JournalReadResult.records()/tailEventId()/gap()
JournalSyncService.registerSyntheticJournal(...)
JournalSyncService.onSessionSync(...)
```

If prerequisite integration chose different names, update this plan's references in a documentation-only commit
before writing production code. Do not add forwarding interfaces solely to preserve guessed names.

### Task 1: Preserve exact inbound Agent protocol items

**Files:**
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolItem.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolDecoder.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolDecoderTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/transport/AgentTransport.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/transport/JettyHttp2Transport.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/transport/JettyHttp2TransportTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/transport/JettyHttp2LivePeerTest.java`

**Step 1: Write the failing decoder ownership test**

Add a test that appends an unknown fourth field to a valid `INPUT`, decodes it, mutates the source array, and
asserts both the typed command and the exact original bytes remain available:

```java
SequenceDecodeResult<AgentProtocolItem> result = decoder.accept(ByteBuffer.wrap(source));
AgentProtocolItem item = decoded(result).getFirst();
assertThat(item.message()).isInstanceOf(AgentMessage.Input.class);
assertThat(item.encoded().toByteArray()).containsExactly(expectedWithFutureTail);
```

Keep the existing chunk-boundary, valid-prefix, semantic-rejection, terminal-failure, and reset assertions.

**Step 2: Run the decoder test to verify it fails**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolDecoderTest'`

Expected: FAIL because `AgentProtocolItem` does not exist and the decoder returns only `AgentMessage`.

**Step 3: Add the raw-item value and make decoding own the bytes**

Implement the public immutable value:

```java
public record AgentProtocolItem(AgentMessage message, ProtocolBytes encoded) {
    public AgentProtocolItem {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(encoded, "encoded");
    }
}
```

Change `AgentProtocolDecoder` to decode each complete item into `AgentProtocolItem`, copying the exact source
slice through `ProtocolBytes.copyOf(bytes, from, to)`. Do not re-encode `message`.

**Step 4: Run the decoder test to verify it passes**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolDecoderTest'`

Expected: PASS with exact bytes preserved across arbitrary chunks and source mutation.

**Step 5: Write the failing transport delivery tests**

Update transport tests so the control callback receives `AgentProtocolItem` and sees the exact encoded command,
while the session callback still receives its typed `AgentMessage`. Add a live-peer assertion with a known
command carrying a future tail.

**Step 6: Run the transport tests to verify they fail**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.transport.JettyHttp2TransportTest,pro.deta.orion.agentd.transport.JettyHttp2LivePeerTest'
```

Expected: FAIL because `AgentTransport.onControlMessage` still exposes only `AgentMessage`.

**Step 7: Deliver raw control items without changing session-stream semantics**

Change only the control callback to `Consumer<AgentProtocolItem>`. In `JettyHttp2Transport`, pass the complete
item to control receivers and unwrap `item.message()` for existing session-stream receivers. Retain the current
single callback executor, stream-generation fencing, and recoverable/terminal decode behavior.

**Step 8: Run focused protocol and transport tests**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolDecoderTest'`

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.transport.JettyHttp2TransportTest,pro.deta.orion.agentd.transport.JettyHttp2LivePeerTest'
```

Expected: PASS.

**Step 9: Commit the exact-envelope boundary**

```bash
git add agent-protocol/src agentd/src/main/java/pro/deta/orion/agentd/transport \
  agentd/src/test/java/pro/deta/orion/agentd/transport
git commit -m "Preserve exact inbound Agent protocol items"
```

Run: `make test`

Expected: PASS for the full regular Maven test suite.

### Task 2: Add recovery sequence and Java journal-event contracts

**Files:**
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/OperationSequence.java`
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionCommandOutcome.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentMessage.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolCodec.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventType.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventPayload.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolCodecTest.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java`
- Test: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolFixtureTest.java`

**Step 1: Write failing unsigned operation-sequence tests**

Mirror the verified unsigned ordering behavior of `EventId`:

```java
OperationSequence low = new OperationSequence(1);
OperationSequence high = OperationSequence.fromUnsigned(BigInteger.ONE.shiftLeft(63));
assertThat(low.compareTo(high)).isNegative();
assertThat(high.toString()).isEqualTo("9223372036854775808");
```

Add `SESSION_SYNC` codec cases with an absent and present appended acknowledged sequence. Legacy three-field
`SESSION_SYNC` must remain readable.

**Step 2: Run the control codec test to verify it fails**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolCodecTest'`

Expected: FAIL because `OperationSequence` and the `SESSION_SYNC` tail do not exist.

**Step 3: Implement the strong value and `SESSION_SYNC` tail**

Implement `OperationSequence` with the same unsigned-64 conversion and comparison shape as `EventId`. Extend
`AgentMessage.SessionSync` with `Optional<OperationSequence> acknowledgedOperationSequence`; encode it as the
next appended field and accept the legacy absence. Keep `afterEventId` and the operation prefix distinct.
This leaf owns the shared Java wire contract and compatibility tests. The server replication task owns deriving
the value from its committed journal projection and populating outbound `SESSION_SYNC`; do not implement that
server behavior here.

**Step 4: Run the control codec test to verify it passes**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.AgentProtocolCodecTest'`

Expected: PASS, including old message readability.

**Step 5: Write failing journal-event and fixture tests**

Use the numeric event allocations and payload order already frozen by the integrated native fixture. Add typed
round trips for:

```java
new SessionEventPayload.ProcessStarted(processId)
new SessionEventPayload.CommandAccepted(sequence, exactEnvelope)
new SessionEventPayload.CommandResult(sequence, commandId, outcome, detail)
new SessionEventPayload.SessionStartFailed(commandId, diagnostic, omittedByteCount)
```

`SessionCommandOutcome` contains `SUCCEEDED`, `FAILED`, `REJECTED`, and `AMBIGUOUS`; do not reuse the direct
control-message `CommandOutcome.DUPLICATE` value. Verify unknown records remain byte-for-byte opaque.

**Step 6: Run event tests to verify they fail**

Run:

```bash
make run-test MODULE=agent-protocol \
  TEST='pro.deta.orion.agent.protocol.SessionEventCodecTest,pro.deta.orion.agent.protocol.AgentProtocolFixtureTest'
```

Expected: FAIL because Java does not know the prerequisite host event allocations or payloads.

**Step 7: Implement only the Java-side event model and codec**

Add the typed payload records, bounds, and codec cases. `COMMAND_ACCEPTED` must preserve the raw command byte
string. `COMMAND_RESULT` must expose its command identity and operation sequence for server/local projection.
`SESSION_START_FAILED` must carry the omission count separately from diagnostic text. Do not change the native
writer in this leaf.

**Step 8: Run all Agent protocol tests**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.*Test'`

Expected: PASS with legacy fixtures readable and the new shared native fixtures byte-identical.

**Step 9: Commit the shared Java contracts**

```bash
git add agent-protocol/src
git commit -m "Define Agent command recovery protocol contracts"
```

Run: `make test`

Expected: PASS.

### Task 3: Reconstruct command state from the local journal suffix

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/CommandJournalScanner.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandState.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/CommandJournalScannerTest.java`

**Step 1: Write the failing prefix/suffix maximum test**

Build prerequisite-reader test records with accepted/result sequences `7` and `11`, pass server prefix `9`,
and assert:

```java
SessionCommandState state = scanner.scan(localSession, new EventId(40), new OperationSequence(9));
assertThat(state.nextOperationSequence()).isEqualTo(new OperationSequence(12));
assertThat(state.tailReached()).isTrue();
```

Also assert an empty suffix uses server prefix plus one, and no prefix/suffix starts at one.

**Step 2: Run the scanner test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandJournalScannerTest'`

Expected: FAIL because the scanner and state do not exist.

**Step 3: Implement the minimal independent suffix scan**

Inject the prerequisite `SessionJournalReader`; call `readAfter` with the server `eventId` cursor and iterate with
ordinary loops. Observe only known command/lifecycle payloads while leaving every record available to journal
sync. Compute:

```java
OperationSequence next = maximum(serverAcknowledged, localSuffixMaximum).incrementExact();
```

Return an explicit exhausted result rather than wrapping unsigned `u64`.

**Step 4: Add failing lifecycle and unmatched-intent tests**

Cover `PROCESS_EXITED`, matching accepted/result records, unmatched intent already resolved by the host as
`AMBIGUOUS`, and duplicate observations. Assert metadata state and control `STATUS` never set authoritative exit.

**Step 5: Run the scanner test to verify the new cases fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandJournalScannerTest'`

Expected: FAIL until lifecycle and command-result observations are represented.

**Step 6: Complete immutable recovered state**

`SessionCommandState` should contain the next sequence, journal-authoritative exit flag, observed command results,
and the scanned tail `eventId`. Do not copy metadata `latestTimestamp` into any of these fields.

**Step 7: Add concurrent-tail handoff and failure tests**

Use a fake reader that appends between pages. Prove the scanner reaches a stable tail or subscribes through the
prerequisite reader's handoff without missing the append. Cover retention gap and corrupt suffix as per-session
blocked results rather than exceptions that stop AgentD.

**Step 8: Run the scanner tests to verify they pass**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandJournalScannerTest'`

Expected: PASS.

**Step 9: Commit recovery scanning**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/session/CommandJournalScanner.java \
  agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandState.java \
  agentd/src/test/java/pro/deta/orion/agentd/session/CommandJournalScannerTest.java
git commit -m "Recover AgentD command state from session journals"
```

Run: `make test`

Expected: PASS.

### Task 4: Add bounded per-session serial lanes

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/ServerSessionCommand.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandScheduler.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionCommandSchedulerTest.java`

**Step 1: Write the failing same-session order test**

Represent a queued command as its typed `AgentMessage`, exact `ProtocolBytes`, and SessionId. Block the first
handler invocation, enqueue three commands, then release it:

```java
assertThat(observed).containsExactly("session-a/one", "session-a/two", "session-a/three");
```

**Step 2: Run the scheduler test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandSchedulerTest'`

Expected: FAIL because the scheduler does not exist.

**Step 3: Implement one drain task per active session lane**

Use a `ConcurrentHashMap<SessionId, Lane>` and a shared executor. Each `Lane` owns an `ArrayDeque`, capacity,
recovery-ready flag, and one `draining` bit. Enqueue under the lane lock; execute handlers outside it. Do not use
a global command lock and do not create a thread solely to enforce each I/O timeout.

**Step 4: Add failing cross-session and recovery-gate tests**

Block session A and prove session B completes. Enqueue before `markRecovered` and prove nothing executes until
the scanner result is installed, then assert the queued commands drain in order.

**Step 5: Run the scheduler test to verify the new cases fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandSchedulerTest'`

Expected: FAIL until lanes are independent and recovery-gated.

**Step 6: Add capacity, lane failure, and close behavior**

Return typed `Accepted`, `Full`, and `Closed` admission results. A handler failure affects only that lane and must
not strand its later work. `close()` stops admission, cancels/drains bounded AgentD work, joins owned workers,
and never sends termination to a host.

**Step 7: Run the scheduler test to verify it passes**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandSchedulerTest'`

Expected: PASS for FIFO, cross-session concurrency, capacity, recovery gating, isolated failure, and close.

**Step 8: Commit the scheduler**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/session/ServerSessionCommand.java \
  agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandScheduler.java \
  agentd/src/test/java/pro/deta/orion/agentd/session/SessionCommandSchedulerTest.java
git commit -m "Add bounded AgentD session command lanes"
```

Run: `make test`

Expected: PASS.

### Task 5: Route established-session controls through the host contract

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandDispatcher.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/TransientCommandReport.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlCommand.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlResult.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionControlClient.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionCommandDispatcherTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionControlClientTest.java`

**Step 1: Write failing happy-path mapping tests for all four controls**

For `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE`, assert the dispatcher sends the same allocated sequence and
exact server envelope to the prerequisite native control client:

```java
assertThat(sent.operationSequence()).isEqualTo(new OperationSequence(12));
assertThat(sent.commandEnvelope()).isEqualTo(serverItem.encoded());
assertThat(sent.command()).isEqualTo(serverItem.message());
```

**Step 2: Run dispatcher tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionCommandDispatcherTest'`

Expected: FAIL because the dispatcher does not exist and current commands lack the shared envelope/sequence.

**Step 3: Adapt the Java control client to the integrated native contract**

Remove v1's INPUT-only retry rule. Every established-session request now uses the prerequisite host's exact
operation retry contract and returns either a durable result event ID, a semantic rejection, or a transient
delivery failure. Rename `journalTimestamp` to `resultEventId`; use `EventId`, not signed-positive `long`.
Continue using `OperationDeadline` and transport-native cancellation; do not add per-call timeout threads.

**Step 4: Run local control tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.session.NativeControlCodecTest,pro.deta.orion.agentd.session.SessionControlClientTest'
```

Expected: PASS for exact retry of all four operations, durable result IDs, rejection, framing, timeout, and
ambiguous transport failure.

**Step 5: Implement routing and transient reports**

Look up the `LocalSession` by server SessionId, reject a journal-authoritative exited state before delivery,
and route through its manifest endpoint. A host result event ID is a wake-up hint for local scanning/upload,
not direct command success. Emit direct control reports only for rejected admission or transient delivery
failures; never emit `CommandOutcome.SUCCEEDED`.

**Step 6: Add failure and duplicate tests**

Cover unknown session, degraded/corrupt recovery, exited journal, host rejection, connection failure, timeout,
same-envelope retry, conflicting sequence reuse rejected by the host, and response loss after durable result.

**Step 7: Run dispatcher and control tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.session.SessionCommandDispatcherTest,pro.deta.orion.agentd.session.SessionControlClientTest'
```

Expected: PASS; no test observes a direct successful `COMMAND_RESULT`.

**Step 8: Commit established-session routing**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/session agentd/src/test/java/pro/deta/orion/agentd/session
git commit -m "Route sequenced AgentD controls to session hosts"
```

Run: `make test`

Expected: PASS.

### Task 6: Route START and create failure-only journals

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/StartSessionHandler.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/LaunchDiagnostic.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/SyntheticSessionJournal.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/StartSessionHandlerTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/journal/SyntheticSessionJournalTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionSpec.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/SessionContractsTest.java`

**Step 1: Write failing START mapping tests**

Cover native runtime selection, existing-directory workspace, environment, terminal bounds, sandbox policy,
and unknown runtime/workspace. Preserve the server SessionId and CommandId; do not allocate operation sequence.

**Step 2: Run START tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.StartSessionHandlerTest'`

Expected: FAIL because the handler and complete mapping do not exist.

**Step 3: Implement validation and runtime dispatch**

Convert `AgentMessage.StartSession` into the existing `SessionSpec`/`WorkspaceReference` model with explicit
typed rejection for unsupported runtime, managed workspace, environment, or policy. On launch completion,
probe through the prerequisite journal reader rather than inferring start outcome from `SessionLaunchResult` or
control `STATUS`.

**Step 4: Add failing journal-present outcome tests**

Test both `PROCESS_STARTED` and journaled `SESSION_START_FAILED`. Also test a host-created empty journal: the
handler must not synthesize a competing failure; it reports the contract violation and leaves server completion
pending for journal reconciliation.

**Step 5: Run START tests to verify the new cases fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.StartSessionHandlerTest'`

Expected: FAIL until journal outcome routing replaces direct success.

**Step 6: Write failing diagnostic-boundary tests**

Use ASCII bytes at the boundary so byte counts are exact:

```java
assertThat(bound.detailBytes()).hasSize(1024 * 1024);
assertThat(bound.detailBytes()).startsWith(first64KiB);
assertThat(bound.detailBytes()).endsWith(last960KiB);
assertThat(bound.omittedByteCount()).isEqualTo(original.length - 1024 * 1024L);
```

Cover below limit, exact limit, one byte over, and a multi-megabyte input. This class truncates only; it must not
claim redaction. Do not add secret-pattern tests here.

**Step 7: Implement the bounded in-memory failure journal**

When and only when no journal exists, encode one `SESSION_START_FAILED` record with `eventId = 1`, CommandId,
bounded diagnostic, and omission count. `SyntheticSessionJournal` exposes immutable record bytes to the
prerequisite journal-sync registration seam and releases them after durable server acknowledgement. It writes no
file and stores no cursor.

**Step 8: Add reconnect and no-file tests**

Fail the first synthetic upload, retry within the same process, acknowledge the second, and assert identical
record bytes. Verify the session directory contains no AgentD failure or cursor file. Model AgentD restart by
discarding the object and redelivering the same START identity, not by loading local state.

**Step 9: Run START and synthetic-journal tests**

Run:

```bash
agentd_start_tests='pro.deta.orion.agentd.session.StartSessionHandlerTest,'\
'pro.deta.orion.agentd.journal.SyntheticSessionJournalTest,'\
'pro.deta.orion.agentd.runtime.SessionContractsTest'
make run-test MODULE=agentd TEST="$agentd_start_tests"
```

Expected: PASS for journaled outcomes, failure-only record, exact bound, reconnect, and no local persistence.

**Step 10: Commit START routing**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/session \
  agentd/src/main/java/pro/deta/orion/agentd/journal/SyntheticSessionJournal.java \
  agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionSpec.java \
  agentd/src/test/java/pro/deta/orion/agentd/session \
  agentd/src/test/java/pro/deta/orion/agentd/journal \
  agentd/src/test/java/pro/deta/orion/agentd/runtime/SessionContractsTest.java
git commit -m "Route AgentD session starts through journal outcomes"
```

Run: `make test`

Expected: PASS.

### Task 7: Observe live journal lifecycle and command results

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionJournalObserver.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionJournalObserverTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionCommandScheduler.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionCommandSchedulerTest.java`

**Step 1: Write the failing observation tests**

Feed `COMMAND_ACCEPTED`, matching `COMMAND_RESULT`, `PROCESS_STARTED`, and `PROCESS_EXITED` records in event order.
Assert duplicate observations are harmless, result observations wake replication, and only `PROCESS_EXITED`
marks process completion.

**Step 2: Run observer tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.SessionJournalObserverTest'`

Expected: FAIL because the observer does not exist.

**Step 3: Implement one observation path for recovery and live tailing**

Use the same `observe(SessionEventRecord)` method from `CommandJournalScanner` and the prerequisite live-tail
callback. Track event order and operation facts in the lane state. Do not create a second journal reader or
advance the HTTP/2 replication cursor.

**Step 4: Add the queued-command/exit race test**

Queue a command behind a blocked operation, publish `PROCESS_EXITED`, then unblock. Assert the queued command is
not delivered locally and gets a transient non-completion report. Feed status-exited without the journal event
and assert it does not make the same transition.

**Step 5: Run observer and scheduler tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.session.SessionJournalObserverTest,pro.deta.orion.agentd.session.SessionCommandSchedulerTest'
```

Expected: PASS.

**Step 6: Commit journal observation**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/session \
  agentd/src/test/java/pro/deta/orion/agentd/session
git commit -m "Observe AgentD command outcomes from session journals"
```

Run: `make test`

Expected: PASS.

### Task 8: Dispatch post-handshake server commands

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentControlHandler.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/CommandOrchestrator.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/CommandOrchestratorTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentControlService.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlServiceTest.java`

**Step 1: Write the failing post-WELCOME dispatch test**

Have the fake transport send `WELCOME`, then an exact `INPUT` item. Assert the handler receives only the second
item and retains its raw bytes. Also assert a command before `WELCOME` still fails negotiation.

**Step 2: Run the control-service test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.core.AgentControlServiceTest'`

Expected: FAIL because `AgentControlService.receiveControl` ignores every message after negotiation.

**Step 3: Add handshake-gated delegation**

Inject `AgentControlHandler`. Before negotiation it accepts only `WELCOME`; afterward it delegates the complete
`AgentProtocolItem`. Handler failure must be isolated/logged without throwing on the Jetty callback executor.
Keep credential clearing and handshake timeout behavior unchanged.

**Step 4: Write failing orchestrator routing tests**

Cover all five command types, `SESSION_SYNC` recovery input, unknown server-direction messages, queue full,
closed orchestrator, and per-session initialization. Assert the orchestrator delegates sync cursor/prefix to
journal sync plus recovery scanning and never parses metadata timestamps as cursors.

**Step 5: Run orchestrator tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.CommandOrchestratorTest'`

Expected: FAIL because `CommandOrchestrator` does not exist.

**Step 6: Implement orchestration coordination**

Route START to `StartSessionHandler`; route the four controls through the scheduler and dispatcher. On
`SESSION_SYNC`, start journal upload through the prerequisite service and independently scan the local suffix.
Mark the lane recovered as soon as the scan reaches its stable tail, even if upload remains backlogged.

**Step 7: Add backlog independence and isolation tests**

Hold the journal upload future open, finish the local scan, and assert a command executes. Hold one session scan
or command and assert another session plus a heartbeat/control send remains available.

**Step 8: Run control and orchestrator tests**

Run:

```bash
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.core.AgentControlServiceTest,pro.deta.orion.agentd.session.CommandOrchestratorTest'
```

Expected: PASS.

**Step 9: Commit transport dispatch**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/core \
  agentd/src/main/java/pro/deta/orion/agentd/session \
  agentd/src/test/java/pro/deta/orion/agentd/core \
  agentd/src/test/java/pro/deta/orion/agentd/session
git commit -m "Dispatch server commands through AgentD orchestration"
```

Run: `make test`

Expected: PASS.

### Task 9: Assemble lifecycle, shutdown, and recovery services

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/Agent.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentConfiguration.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentAssemblyTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentConfigurationTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentLifecycleTest.java`

**Step 1: Write the failing assembly-order test**

Assert process lock starts before local discovery/recovery and transport connection; command handlers are
registered before connect; and a discovered session begins recovery only after the server supplies
`SESSION_SYNC` cursor and acknowledged operation sequence.

**Step 2: Run assembly tests to verify they fail**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.core.AgentAssemblyTest'`

Expected: FAIL because `Agent.create` currently assembles only the process lock and handshake service.

**Step 3: Add bounded orchestration configuration**

Add explicit positive defaults for per-session queue capacity, recovery concurrency, initialization timeout,
and local-control timeout. Keep them internal/defaulted unless an existing configuration source already owns
the values; do not add speculative CLI options.

**Step 4: Assemble existing prerequisite and task-owned services**

Construct discovery/registry, native runtime, prerequisite journal reader/sync, scanner/observer, scheduler,
dispatcher/start handler, command orchestrator, and control service. Choose lifecycle order so callbacks cannot
reach an unstarted service and reverse close cannot terminate hosts.

**Step 5: Add failing shutdown/isolation tests**

Close with queued and active work, a blocked journal upload, and multiple sessions. Assert AgentD-owned workers
stop, outbound sends fail boundedly, and no local `TERMINATE` is sent. Assert one recovery failure does not close
the transport or another lane.

**Step 6: Run assembly, configuration, and lifecycle tests**

Run:

```bash
agentd_assembly_tests='pro.deta.orion.agentd.core.AgentAssemblyTest,'\
'pro.deta.orion.agentd.core.AgentConfigurationTest,'\
'pro.deta.orion.agentd.core.AgentLifecycleTest'
make run-test MODULE=agentd TEST="$agentd_assembly_tests"
```

Expected: PASS.

**Step 7: Commit production assembly**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/core \
  agentd/src/test/java/pro/deta/orion/agentd/core
git commit -m "Assemble AgentD command orchestration lifecycle"
```

Run: `make test`

Expected: PASS.

### Task 10: Verify the end-to-end AgentD command flow

**Files:**
- Create: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentCommandLivePeerTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlLivePeerTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`

**Step 1: Write a failing live control-stream command test**

Extend the existing TLS HTTP/2 peer pattern: send `WELCOME`, a `SESSION_SYNC` with prefix/cursor, then commands.
Use fake prerequisite journal/control boundaries so the test observes exact envelope bytes, assigned sequence,
and no direct success result.

**Step 2: Run the live-peer test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.core.AgentCommandLivePeerTest'`

Expected: FAIL because the end-to-end command assembly is not wired.

**Step 3: Complete only missing wiring exposed by the test**

Keep the live peer bounded and deterministic. Do not add retry loops to the test or production code to hide
ordering races.

**Step 4: Add crash-window and start-failure scenarios**

Cover:

```text
crash before COMMAND_ACCEPTED -> server redelivery may execute
crash after COMMAND_ACCEPTED -> recovered COMMAND_RESULT(AMBIGUOUS), no effect retry
crash after COMMAND_RESULT before local reply -> original result eventId returned
large upload backlog -> local suffix scan enables commands independently
PROCESS_EXITED -> later command is not delivered
pre-journal START failure -> one in-memory eventId=1 failure record
```

**Step 5: Run live-peer, runtime, and session tests**

Run:

```bash
agentd_flow_tests='pro.deta.orion.agentd.core.AgentCommandLivePeerTest,'\
'pro.deta.orion.agentd.core.AgentControlLivePeerTest,'\
'pro.deta.orion.agentd.runtime.NativeRuntimeTest,pro.deta.orion.agentd.session.*Test'
make run-test MODULE=agentd TEST="$agentd_flow_tests"
```

Expected: PASS.

**Step 6: Commit end-to-end coverage**

```bash
git add agentd/src/test/java/pro/deta/orion/agentd
git commit -m "Cover AgentD command orchestration recovery"
```

Run: `make test`

Expected: PASS.

### Task 11: Align protocol and architecture documentation

**Files:**
- Modify: `agent-protocol/protocol/README.md`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/package-info.java`
- Modify: `docs/plans/2026-09-02-agentd.md`
- Modify: `docs/plans/2026-09-03-agentd-command-orchestration-design.md`

**Step 1: Update the protocol reference**

Document the appended `SESSION_SYNC` acknowledged operation prefix, exact raw command-envelope preservation,
new journal event payloads, and the distinction among `eventId`, `operationSequence`, and metadata
`latestTimestamp`. Preserve the v1 compatibility section and fixture names.

**Step 2: Replace obsolete local-control comments**

Update the session package comment that currently says no general operation sequence and INPUT-only retry.
State all-four sequencing, journal-result authority, and the intentionally deferred host-incarnation proof.

**Step 3: Align the broad AgentD plan**

Replace only the superseded claims that say there is no acknowledgement protocol, require separate
`SESSION_STARTED`, or treat a direct successful `COMMAND_RESULT` as durable completion. Link to the approved
design for detailed recovery and crash semantics. Also replace the INPUT-only retry/no-general-sequence claim
and timestamp-named cursor language with the all-four sequence contract and `eventId` terminology. Do not
rewrite unrelated AgentD sections.

**Step 4: Run documentation checks**

Run: `git diff --check`

Expected: no output and exit zero.

**Step 5: Commit documentation alignment**

```bash
git add agent-protocol/protocol/README.md \
  agentd/src/main/java/pro/deta/orion/agentd/session/package-info.java \
  docs/plans/2026-09-02-agentd.md \
  docs/plans/2026-09-03-agentd-command-orchestration-design.md
git commit -m "Align AgentD command orchestration documentation"
```

Run: `make test`

Expected: PASS because the commit includes a Java package source even though its behavioral content is comments.

### Task 12: Final verification and review preparation

**Files:**
- Inspect: all files changed from the rebased task base
- Inspect: `docs/reviews/RULES.md`
- Inspect: every changed class-level `@AiRule` comment

**Step 1: Review the complete branch diff**

Run: `git status --short`

Expected: no output.

Run: `git diff --check main...HEAD`

Expected: no output.

Run: `git diff --stat main...HEAD`

Expected: only Agent protocol, AgentD, compatibility-fixture, and scoped documentation changes described above;
no native host implementation, server implementation, or redaction implementation.

**Step 2: Run focused regression groups once more**

Run: `make run-test MODULE=agent-protocol TEST='pro.deta.orion.agent.protocol.*Test'`

Run:

```bash
agentd_regression_tests='pro.deta.orion.agentd.session.*Test,'\
'pro.deta.orion.agentd.core.AgentCommandLivePeerTest,'\
'pro.deta.orion.agentd.core.AgentAssemblyTest'
make run-test MODULE=agentd TEST="$agentd_regression_tests"
```

Expected: PASS.

**Step 3: Run routine development verification**

Run: `mvn verify -Pdev -T 4`

Expected: `BUILD SUCCESS`.

**Step 4: Run the required full project tests**

Run: `make test`

Expected: PASS.

**Step 5: Confirm deferred and delegated work stayed out of scope**

Verify:

```text
no hostInstanceId was added
no AgentD cursor or failure file was added
no native reader/sync/ACK implementation was copied into this leaf
no server command projection was implemented
no secret redaction implementation was added
no direct successful COMMAND_RESULT or separate SESSION_STARTED was added
```

Do not add tests whose only purpose is proving removed legacy behavior is absent; establish these from the
positive contract tests and the reviewed diff.

**Step 6: Hand off for review**

Report the task path, rebased base SHA, branch/head, commit list, changed files, focused and full verification,
the host-incarnation risk, and the still-required diagnostic-redaction/server-projection follow-ups. Do not
squash, delete the task node, cherry-pick, or clean up until the orchestrator review and user gate request it.

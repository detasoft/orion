# Native Git Client Session Machines Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add native upload-pack and receive-pack clients whose service-specific
state machines are driven over a transport session by one shared session
machine.

**Architecture:** Public clients are thin facades over
`GitProtocolSessionMachine`. The session machine interprets explicit actions
from a `GitClientMachine<R>`, while upload-pack and receive-pack machines own
their semantic protocol phases and compose the production evolution of
`GitMinimalWireMachine` for inbound framing and raw pack routing. Every session,
client, and wire machine stores its current continuation in the reusable
`ContinuationRuntime<I>` abstraction and advances through `ContinuationFlow`.

**Tech Stack:** Java 21, Maven, Netty `ByteBuf`, JUnit 5, AssertJ

---

### Implemented foundation: Continuation runtime

`core/lifecycle-state-machine` now provides `Continuation<I>`,
`ContinuationFlow<I>`, `ContinuationRuntime<I>`, and
`TimedContinuation<I>`/`TimedContinuationRuntime<I>`. A continuation returns an
explicit flow: continue, await, or transition. Success and error completion are
terminal continuations. Timed continuations declare their own timeout duration,
while the timed runtime owns the clock and timeout transition policy.

The client implementation should compose this runtime rather than adding another
generic state-machine abstraction. Domain continuations pass intermediate values
through fields and constructors; runtime hooks provide test/debug observation.

### Task 1: Define the client-machine action boundary

**Files:**
- Modify: `core/git-protocol-client/pom.xml`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/machine/GitClientAction.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/machine/GitClientMachine.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/machine/GitProtocolClientException.java`
- Test: existing session-machine tests under
  `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/machine`

**Step 1: Write the failing contract test**

Define a small test machine with continuation objects and verify this action
sequence:

```text
Write(request) -> Read -> Complete(result)
```

Also verify:

- a write action owns a non-null readable `ByteBuf`;
- `written()` is legal only after a write action;
- `accept(ByteBuf)` is legal only after a read action;
- `endOfInput()` while waiting for input creates a typed failure action;
- `close()` releases an unconsumed outbound buffer;
- terminal complete and failure actions cannot advance.

The test should express the desired API:

```java
GitClientAction<String> action = machine.action();
assertThat(action).isInstanceOf(GitClientAction.Write.class);
machine.written();
assertThat(machine.action()).isInstanceOf(GitClientAction.Read.class);
```

`GitClientMachine.accept` returns whether the caller should release its
original input reference, matching the current wire-machine ownership
contract.

The test machine should expose only the `GitClientMachine` action contract.
Generic continuation ownership belongs to `ContinuationRuntime`, not to a
separate contract test for the client interface.

**Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitProtocolSessionMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails where session-machine action support is still
missing.

**Step 3: Implement the minimal action model**

Add a direct production dependency on `lifecycle-state-machine`; do not rely on
the provided-scope dependency of `git-parser`.

Use a sealed generic action family:

```java
public sealed interface GitClientAction<R> {
    record Write<R>(ByteBuf chunk) implements GitClientAction<R> {}
    record Read<R>() implements GitClientAction<R> {}
    record Complete<R>(R result) implements GitClientAction<R> {}
    record Fail<R>(GitProtocolClientException failure)
            implements GitClientAction<R> {}
}
```

Define the machine boundary:

```java
public interface GitClientMachine<R> extends AutoCloseable {
    GitProtocolService service();
    GitClientAction<R> action();
    void written();
    boolean accept(ByteBuf input);
    void endOfInput();
    @Override
    void close();
}
```

Service-specific implementations of this interface compose continuations that
return `ContinuationFlow`. The interface defines the session-facing events and
actions, while the runtime owns the current continuation and terminal state.

`GitProtocolClientException` is checked and contains:

- an `Operation` enum: `ADVERTISEMENT`, `NEGOTIATION`, `PACK`, `STATUS`,
  `SESSION`;
- a non-empty sanitized message;
- an optional cause.

Do not store a URI, credentials, or raw protocol bytes.

**Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add core/git-protocol-client/src/main/java/pro/deta/orion/git/client/machine \
  core/git-protocol-client/src/test/java/pro/deta/orion/git/client/machine
git commit -m "Add native Git client machine actions"
```

### Task 2: Drive a client machine through one protocol session

**Files:**
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/machine/GitProtocolSessionMachine.java`
- Modify: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/ScriptedGitProtocolTransport.java`
- Test: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/machine/GitProtocolSessionMachineTest.java`

**Step 1: Write the failing happy-path test**

Create a deterministic test client machine that emits one write, requests two
fragmented reads, and completes with a string result. Drive it with
`ScriptedGitProtocolTransport` and assert:

- the exact service, URI, and options are passed to `open`;
- the exact outbound bytes are written;
- both inbound chunks reach the client machine in order;
- the returned result is the complete action's result;
- inbound and outbound buffers are released;
- both client and session close exactly once.

The desired entry point is:

```java
R run(
        URI remoteUri,
        GitProtocolTransportOptions options,
        GitClientMachine<R> clientMachine)
        throws GitProtocolTransportException, GitProtocolClientException;
```

The service comes from `clientMachine.service()`.

**Step 2: Run the happy-path test and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitProtocolSessionMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `GitProtocolSessionMachine` does not exist.

**Step 3: Implement the session phases and action loop**

Store session lifecycle state in a `ContinuationRuntime` with explicit
continuations:

```text
Open -> Exchange -> Closing -> Complete
                         \-> Failed
```

The synchronous runner interprets the current phase's action:

```text
Write    -> session.write, release action chunk, machine.written
Read     -> session.read, machine.accept or machine.endOfInput
Complete -> return result
Fail     -> throw the typed client failure
```

Each I/O outcome advances the current continuation. The session runtime must not
inspect Git packet content or maintain a second independent phase field.

**Step 4: Run the happy-path test and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 5: Add failing cleanup tests**

Cover:

- open failure does not call session close but does close the client machine;
- write and read failures close both machines;
- client failure closes both machines;
- a close failure becomes primary when the exchange succeeded;
- a close failure is suppressed when another failure is already primary;
- `null` from `session.read()` calls `endOfInput()` instead of accepting an
  empty buffer.

Extend the scripted fixture with configured close failure and counters only as
needed by these tests.

**Step 6: Run cleanup tests and verify RED**

Run the command from Step 2.

Expected: at least the close-failure and suppression cases fail.

**Step 7: Implement cleanup semantics**

Close the client machine and opened session in `finally`. Preserve the primary
failure and attach later close failures with `addSuppressed`. Do not wrap a
transport exception merely to change its message.

**Step 8: Run all session-machine tests and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 9: Commit**

```bash
git add core/git-protocol-client/src/main/java/pro/deta/orion/git/client/machine \
  core/git-protocol-client/src/test/java/pro/deta/orion/git/client/machine \
  core/git-protocol-client/src/test/java/pro/deta/orion/git/client/ScriptedGitProtocolTransport.java
git commit -m "Drive native Git clients through session machine"
```

### Task 3: Add the upload-pack client machine

**Prerequisite:** The active wire-core task must provide the production
wire-machine boundary plus typed v1 advertisement, protocol v2 `ls-refs`, and
protocol v2 fetch response parsers. Read their final APIs before starting this
task. Do not implement temporary line parsing in `git-protocol-client`.

**Files:**
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/uploadpack/GitUploadPackClient.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/uploadpack/GitUploadPackClientMachine.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/uploadpack/GitUploadPackDiscoveryRequest.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/uploadpack/GitUploadPackDiscoveryResult.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/uploadpack/GitUploadPackFetchRequest.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/uploadpack/GitUploadPackFetchResult.java`
- Test: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/uploadpack/GitUploadPackClientMachineTest.java`
- Test: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/uploadpack/GitUploadPackClientTest.java`

**Step 1: Write the failing discovery tests**

Feed the machine an advertisement in multiple chunks and verify that it emits
an exact protocol v2 `ls-refs` request containing only requested ref prefixes,
`symrefs`, and `peel`. Feed a terminated `ls-refs` response and assert that the
complete result preserves ref names, object ids, optional peeled ids, optional
symbolic targets, and advertised capabilities.

Also cover an empty repository and a malformed/truncated response.

**Step 2: Run discovery tests and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitUploadPackClientMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the upload-pack client types do not exist.

**Step 3: Implement discovery phases**

Use explicit phase objects:

```text
Advertisement -> WriteLsRefs -> LsRefsResponse -> Complete
```

Store these phases as upload-pack continuations driven by
`ContinuationRuntime`.
Compose the final wire-core machine and parsers. Build outbound packets with
`GitPktLineWriter`. Do not concatenate inbound transport chunks or parse raw
lines in the client module.

**Step 4: Run discovery tests and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 5: Write failing fetch tests**

Cover:

- one explicit want;
- optional depth;
- `filter blob:none` only when advertised;
- side-band-64k selection only when advertised;
- fragmented acknowledgments and section delimiters;
- exact band-1 bytes written to a supplied `GitPackWriter`;
- progress separated from pack bytes;
- side-band fatal error;
- pack writer aborted when the response fails;
- pack writer completed only after a valid response terminator.

**Step 6: Run fetch tests and verify RED**

Run the command from Step 2.

Expected: fetch tests fail because fetch phases are absent.

**Step 7: Implement fetch phases**

Use explicit phase objects:

```text
Advertisement -> WriteFetch -> FetchSections -> Pack -> Complete
```

Use the same upload-pack continuation runtime for the entire operation.
Let the wire machine switch to the raw or side-band target. Enforce
`maximumPackBytes` while forwarding, without accumulating a second complete
pack.

**Step 8: Run upload-pack machine tests and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 9: Add and test the thin facade**

`GitUploadPackClient` stores a `GitProtocolSessionMachine`. Its `discover` and
`fetch` methods create one upload-pack machine and return the session machine's
result. The facade contains no packet parsing and no read/write loop.

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitUploadPackClientTest,GitUploadPackClientMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 10: Commit**

```bash
git add core/git-protocol-client/src/main/java/pro/deta/orion/git/client/uploadpack \
  core/git-protocol-client/src/test/java/pro/deta/orion/git/client/uploadpack
git commit -m "Add native upload-pack client machine"
```

### Task 4: Add the receive-pack client machine

**Prerequisite:** The active wire-core task must provide the final typed v1
reference advertisement parser. Reuse the existing report-status and side-band
primitives. Do not implement an advertisement parser in the client module.

**Files:**
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/receivepack/GitReceivePackClient.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/receivepack/GitReceivePackClientMachine.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/receivepack/GitReceivePackPushRequest.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/receivepack/GitReceivePackPushResult.java`
- Test: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/receivepack/GitReceivePackClientMachineTest.java`
- Test: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/receivepack/GitReceivePackClientTest.java`

**Step 1: Write the failing command tests**

Feed a fragmented receive-pack advertisement and verify that one-ref push emits:

```text
<old-id> <new-id> <ref-name>\0report-status side-band-64k
flush
raw pack chunks
```

Select only advertised capabilities. Verify the source `GitPackReader` chunks
are released after write and their reader indexes remain unchanged during the
session write.

Cover ref creation with the all-zero old id and ref update with a concrete old
id.

**Step 2: Run command tests and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitReceivePackClientMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the receive-pack client types do not exist.

**Step 3: Implement advertisement, command, and pack phases**

Use explicit phase objects:

```text
Advertisement -> WriteCommand -> WritePack -> ReportStatus -> Complete
```

Store these phases as receive-pack continuations driven by
`ContinuationRuntime`.
Require `report-status`. Select `side-band-64k` when available. Do not build or
inspect pack contents.

**Step 4: Run command tests and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 5: Write failing result and error tests**

Cover:

- unpack success with accepted ref;
- unpack failure;
- per-ref remote rejection;
- side-band progress;
- side-band fatal error;
- missing report-status capability;
- unexpected end before flush;
- session/client/pack reader close on every failure.

**Step 6: Run result tests and verify RED**

Run the command from Step 2.

Expected: the new result and failure cases fail.

**Step 7: Implement report-status and terminal phases**

Compose `GitReportStatusParser` and `GitSideBandDecoder`. Return a typed push
result that retains the parsed report status and selected capabilities.

**Step 8: Run receive-pack machine tests and verify GREEN**

Run the command from Step 2.

Expected: PASS.

**Step 9: Add and test the thin facade**

`GitReceivePackClient.push` constructs one receive-pack machine and delegates
the complete exchange to `GitProtocolSessionMachine`.

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitReceivePackClientTest,GitReceivePackClientMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 10: Commit**

```bash
git add core/git-protocol-client/src/main/java/pro/deta/orion/git/client/receivepack \
  core/git-protocol-client/src/test/java/pro/deta/orion/git/client/receivepack
git commit -m "Add native receive-pack client machine"
```

### Task 5: Verify the module and finish task tracking

**Files:**
- Modify: `TASKS.md`

**Step 1: Run all protocol-client tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 2: Run routine development verification**

Run:

```bash
mvn verify -Pdev -q -pl core/git-protocol-client -am
```

Expected: PASS.

**Step 3: Check buffer ownership and dependency boundaries**

Confirm:

- every created or returned `ByteBuf` has one documented release path;
- no service machine buffers a complete pack;
- no production source imports JGit;
- service machines do not call `GitProtocolSession`;
- `GitProtocolSessionMachine` does not parse Git packets;
- no client module code duplicates advertisement or v2 response parsing.
- session, upload-pack, receive-pack, and wire machines compose continuation
  runtimes instead of duplicating generic continuation ownership rules.

**Step 4: Finish task tracking**

Mark the selected client-state-machine task complete and remove its owner line.
Do not change other owners or active tasks.

**Step 5: Review the final diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. Unrelated files owned by other active tasks
remain unstaged.

**Step 6: Commit task tracking**

Stage only this task's `TASKS.md` lines and commit:

```bash
git commit -m "Complete native Git client state machines"
```

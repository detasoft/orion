# AgentD Session Runtime and Control Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a native session runtime and bounded local-control client that hand off durable sessions without making AgentD their lifetime owner.

**Architecture:** Keep immutable runtime contracts in `agentd.runtime` and native control framing/transports in `agentd.session`. `NativeRuntime` composes the existing manifest and journal probes with STATUS, while an injected tentative-process boundary makes pre-handoff cleanup precise and post-handoff ownership impossible.

**Tech Stack:** Java 21, JDK Unix-domain `SocketChannel`/`Selector`, existing Jackson Core manifest reader, JUnit 5, AssertJ.

---

### Task 1: Runtime and workspace contracts

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/WorkspaceReference.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/WorkspaceResolver.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/ExistingDirectoryWorkspaceResolver.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionSpec.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionRuntime.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionLaunchResult.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/runtime/SessionContractsTest.java`

**Steps:**
1. Write tests for immutable copies, safe existing-directory resolution, managed-workspace rejection, unsupported arbitrary environment, and typed validation failures.
2. Run `mvn test -Pdev -T 4 -q -pl agentd -am -Dtest=SessionContractsTest -Dsurefire.failIfNoSpecifiedTests=false` and confirm the new API is missing.
3. Implement the smallest sealed workspace/result models and existing-directory resolver that satisfy the tests without filesystem mutation outside the selected workspace.
4. Run the focused command again and confirm it passes.
5. Commit with `Define AgentD session runtime contracts`.

### Task 2: Native control codec and typed results

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlCommand.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlResult.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/HostStatus.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/NativeControlCodec.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/NativeControlCodecTest.java`

**Steps:**
1. Write tests for exact v1 INPUT/RESIZE/SIGNAL/TERMINATE/STATUS frames, CRC-32C, 16 MiB bounds, copied request IDs, typed ACCEPTED/DUPLICATE/ERROR/STATUS responses, and malformed payload rejection.
2. Run the focused Maven command with `-Dtest=NativeControlCodecTest` and confirm the codec is missing.
3. Implement fixed-size little-endian framing with immutable payload copies and results that always retain the Agent `CommandId` when one exists.
4. Re-run the focused test and confirm it passes.
5. Commit with `Implement native session control framing`.

### Task 3: Deadline-bound Unix control client

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlTransport.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlTransportFactory.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/UnixDomainControlTransport.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionControlClient.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionControlClientTest.java`

**Steps:**
1. Write live Unix-socket tests for STATUS, host ERROR, checksum/framing failure, response timeout, a new connection after failure, exact-frame INPUT retry/duplicate response, and no retry after ambiguous RESIZE delivery. Add a named-pipe endpoint test expecting typed unsupported transport.
2. Run the focused Maven command with `-Dtest=SessionControlClientTest` and confirm the client is missing.
3. Implement one nonblocking channel and selector per whole request, applying one monotonic deadline across connect/write/read. Retry only INPUT with the same request ID, input UUID, and bytes after an ambiguous transport failure.
4. Re-run the focused test and confirm it passes; inspect threads to ensure no per-I/O worker exists.
5. Commit with `Add bounded native session control client`.

### Task 4: STATUS-backed discovery probe

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlHostProbe.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/ControlHostProbeTest.java`

**Steps:**
1. Write tests showing STATUS host-live is required, child state comes from STATUS, manifest PID mismatch is unreachable, and metadata state/timestamps are irrelevant.
2. Run the focused Maven command with `-Dtest=ControlHostProbeTest` and confirm the probe is missing.
3. Implement the adapter over `SessionControlClient`, treating PID equality as correlation rather than identity proof.
4. Re-run the focused test and confirm it passes.
5. Commit with `Probe session hosts through native status`.

### Task 5: Detached native launch and durable handoff

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/DetachedProcessLauncher.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/runtime/NativeRuntime.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/runtime/DetachedProcessLauncherTest.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/runtime/DetachedProcessLauncherFixture.java`

**Steps:**
1. Write tests for command construction, stdio redirection, successful manifest+journal+STATUS handoff, collision, invalid executable/workspace/policy, unsupported environment, early exit, missing-journal timeout, exact-process cleanup, preservation when exit cannot be confirmed, and no termination after success.
2. Add a subprocess fixture proving a redirected child remains alive after the launching JVM exits.
3. Run the focused Maven command with `-Dtest=NativeRuntimeTest,DetachedProcessLauncherTest` and confirm the implementation is missing.
4. Implement exclusive directory creation, bounded polling through existing probes, tentative-process-only cleanup, recursive deletion only after confirmed exit, and a started result with no process handle.
5. Re-run the focused tests and confirm they pass.
6. Commit with `Launch detached native session hosts`.

### Task 6: Integration verification and documentation

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/package-info.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/package-info.java`
- Modify if needed: `agentd/pom.xml`

**Steps:**
1. Confirm no broad dependency is needed and document the native v1 operation-sequence, named-pipe, and incarnation-proof gaps at package boundaries.
2. Run `mvn test -Pdev -T 4 -q -pl agentd -am` outside the sandbox.
3. Run `mvn verify -Pdev -T 4` outside the sandbox.
4. Run `git diff --check` and inspect `git status --short` plus the complete branch diff from `2de6bb4`.
5. Commit any final scoped cleanup with a single-line message, then request primary review without squashing, deleting the task node, or integrating to main.

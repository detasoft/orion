# Native Git Virtual Thread Transport Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable the native Git TCP transport with virtual-thread-per-connection handling and suppress the noisy Apache SSHD NIO2 factory INFO line.

**Architecture:** Keep the Git protocol logic in `GitBlockingWireSession`. `GitNativeTransportService` owns only socket lifecycle, native Git request parsing, connection virtual threads, and service diagnostics. `LogInitializer` suppresses the specific Apache SSHD startup logger at WARN.

**Tech Stack:** Java 21 virtual threads, `ServerSocket`, `Socket`, Orion `BufferedByteInput`/`BufferedByteOutput`, JUnit 5, AssertJ, Maven `-Pdev`.

---

### Task 1: Suppress Apache SSHD NIO2 Factory INFO

**Files:**
- Modify: `core/common/src/main/java/pro/deta/orion/util/LogInitializer.java`
- Modify: `core/common/src/test/java/pro/deta/orion/util/LogInitializerTest.java`

**Step 1: Write the test**

Add an assertion to `appliesUnitTestDebugPropertiesWhenReinitializingLogging`
or a focused test that after `new LogInitializer()`,
`logger("org.apache.sshd.common.io.DefaultIoServiceFactoryFactory").getLevel()`
is `Level.WARN`.

**Step 2: Run the focused test**

Run:
`mvn test -Pdev -T 4 -q -pl core/common -Dtest=LogInitializerTest`

Expected: fail before implementation if the logger has no explicit WARN level.

**Step 3: Implement**

Add this category level in `LogInitializer` constructor:
`org.apache.sshd.common.io.DefaultIoServiceFactoryFactory:WARN`.

**Step 4: Verify**

Run:
`mvn test -Pdev -T 4 -q -pl core/common -Dtest=LogInitializerTest`

Expected: pass.

### Task 2: Add Native Git Request Parsing Tests

**Files:**
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitNativeTransportStateMachineTest.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`

**Step 1: Write tests**

Add package-visible parser tests for native Git daemon request payloads:

- `git-upload-pack /repo.git\0host=localhost\0\0version=2\0` becomes service `UPLOAD_PACK`, repository `repo`, host `localhost`, version `2`.
- malformed requests without a NUL separator throw `IllegalArgumentException`.

**Step 2: Run focused tests**

Run:
`mvn test -Pdev -T 4 -q -pl net/git-transport -am -Dtest=GitNativeTransportStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: fail before parser exists.

**Step 3: Implement parser**

Add a package-visible static `initialRequestData(String request)` helper in
`GitNativeTransportService`. Parse command and metadata from the native Git
daemon initial pkt-line payload, normalize repository path like SSH/HTTP routes,
and preserve only supported Git protocol parameters.

**Step 4: Verify**

Run the same focused test command.

Expected: pass.

### Task 3: Enable Native Git Socket Lifecycle

**Files:**
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitNativeTransportStateMachineTest.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`

**Step 1: Write lifecycle tests**

Add tests that:

- disabled config does not bind and reports not running;
- enabled config with address `127.0.0.1` and port `0` starts, reports running,
  and exposes a positive actual bound port;
- stop closes the listener;
- an accepted connection is handled on a virtual thread through the real
  `GitWireBootstrap` and session path using a repository provider test double.

**Step 2: Run focused tests**

Run:
`mvn test -Pdev -T 4 -q -pl net/git-transport -am -Dtest=GitNativeTransportStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: fail while service is still a placeholder.

**Step 3: Implement lifecycle**

Store config and repository provider in fields. On start, bind `ServerSocket`,
log the actual listener address and port, start an accept loop thread, and
dispatch each accepted socket to `Thread.ofVirtual()`. On stop, close the
listener and wait briefly for the accept loop to exit.

**Step 4: Implement connection bootstrap**

Construct `InputStreamBufferedByteInput` and `OutputStreamBufferedByteOutput`,
then use `GitWireBootstrap` to create `GitBlockingWireTransport` and extract
`InitialRequestData`. Build `GitBlockingWireSession` from the bootstrap result.
Use an anonymous access hook appropriate for native Git if no authenticated
identity exists.

**Step 5: Verify**

Run:
`mvn test -Pdev -T 4 -q -pl git/git-parser,core/common,net/git-transport,net/http-core -am -Dtest=GitWireBootstrapTest,GitBlockingWireTransportTest,LogInitializerTest,GitNativeTransportStateMachineTest,SshCommandFactoryTest,OrionGitRouteNativeTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: pass.

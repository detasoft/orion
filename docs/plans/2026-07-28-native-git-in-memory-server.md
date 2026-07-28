# Native Git In-Memory Server Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Run the native Netty `git://` server with automatically created
ephemeral repositories and complete real Git CLI clone and push operations
through one central server wire machine.

**Architecture:** `GitNativeProtocolAdapter` is reduced to a Netty bridge around
one `GitServerWireMachine` per connection. The server wire machine owns every
Git protocol phase and composes `GitMinimalWireMachine` for pkt-line framing.
`InMemoryNativeGitRepositoryProvider` owns process-local refs and objects and
exposes typed repository operations; no Netty hot path uses
`InputStream`/`OutputStream`.

**Tech Stack:** Java 21, Maven, Netty `ByteBuf`/`EmbeddedChannel`,
`ContinuationRuntime`, JUnit 5, AssertJ, Git CLI

---

### Task 1: Parse the initial service request through the wire machine

**Files:**
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitInitialServiceRequestParser.java`
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitInitialServiceRequestPhases.java`
- Create:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitInitialServiceRequestMachineTest.java`

**Step 1: Write the failing fragmented-input test**

Create a machine through the desired factory:

```java
try (GitMinimalWireMachine machine =
        GitMinimalWireMachine.forInitialServiceRequest(allocator)) {
    acceptAndRelease(machine, fragment("001f...first half..."));
    assertThat(machine.outcome(GitInitialServiceRequest.class)).isEmpty();

    acceptAndRelease(machine, fragment("...second half..."));

    GitInitialServiceRequest request =
            machine.result(GitInitialServiceRequest.class);
    assertThat(request.service())
            .isEqualTo(GitInitialServiceRequest.Service.UPLOAD_PACK);
    assertThat(request.repositoryPath()).isEqualTo("/demo.git");
}
```

Also cover:

- later bytes in the same input remain readable for the next server phase;
- receive-pack;
- unsupported service;
- a control packet instead of a data packet;
- close with incomplete header or payload;
- release of all supplied fragments.

**Step 2: Run the test and verify RED**

```bash
mvn test -Pdev -q -pl core/git-parser -am \
  -Dtest=GitInitialServiceRequestMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because
`GitMinimalWireMachine.forInitialServiceRequest` does not exist.

**Step 3: Add the semantic initial-request phase**

Add:

```java
public static GitMinimalWireMachine forInitialServiceRequest(
        ByteBufAllocator allocator)
```

The factory uses the existing semantic constructor with result type
`GitInitialServiceRequest`. `GitInitialServiceRequestPhases` accepts exactly one
DATA payload and completes with the typed request. Move only payload decoding
into a package-private `GitInitialServiceRequestParser.readPayload(...)`
operation; keep `read(ByteBuf)` as the compatibility entry point.

Do not add an accumulator outside `GitMinimalWireMachine`.

**Step 4: Run the focused parser tests and verify GREEN**

Run the command from Step 2.

**Step 5: Commit**

```bash
git add core/git-parser
git commit -m "Parse initial Git requests through wire machine"
```

### Task 2: Add process-local native repositories and typed operations

**Files:**
- Create:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProvider.java`
- Create:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryOperations.java`
- Modify:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Modify:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeUploadPackService.java`
- Modify:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/service/NativeReceivePackService.java`
- Create:
  `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProviderTest.java`
- Create:
  `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeRepositoryOperationsTest.java`

**Step 1: Write failing provider tests**

Verify:

```java
InMemoryNativeGitRepositoryProvider provider =
        new InMemoryNativeGitRepositoryProvider();

GitRepository first = success(provider.findOrCreate("demo"));
GitRepository second = success(provider.findOrCreate("demo"));

assertThat(first.unwrap(NativeRepositoryOperations.class)).isPresent();
assertThat(second.unwrap(NativeRepositoryOperations.class)).isPresent();
assertThat(provider.exists("demo")).isTrue();
assertThat(provider.find("missing")).isInstanceOf(Result.Failure.class);
```

Also create the same name concurrently and assert one shared repository state.
Two different names must not share refs or objects.

**Step 2: Run provider tests and verify RED**

```bash
mvn test -Pdev -q -pl core/git-native-storage -am \
  -Dtest=InMemoryNativeGitRepositoryProviderTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the provider and operations do not exist.

**Step 3: Implement the provider and repository operation boundary**

The provider uses:

```java
private final ConcurrentMap<String, NativeGitRepository> repositories =
        new ConcurrentHashMap<>();
```

`findOrCreate` validates a normalized nonblank name and calls
`computeIfAbsent`. Each created `NativeGitRepository` owns a new
`LooseRefStore`, `LooseObjectStore`, and `HEAD -> refs/heads/main`.

`NativeGitRepository.unwrap(NativeRepositoryOperations.class)` returns a stable
operations object backed by the repository stores. Closing a returned handle
does not remove provider state.

The typed operation boundary provides:

```java
Map<String, String> refs();
Optional<String> headTarget();
List<GitAdvertisedRef> lsRefs(
        List<String> prefixes,
        boolean symrefs,
        boolean unborn);
NativeFetchResult fetch(NativeFetchRequest request);
ReceiveResult receive(
        ReceivePackCommandSection commands,
        ByteBuf pack);
```

`NativeFetchResult` carries selected object count and owned pack bytes, not
wire-framed packets. Reuse `NativeObjectClosure`, `NoDeltaPackBuilder`, and the
domain portion of `NativeReceivePackService`.

Keep the blocking `NativeGitRepository.upload/receive` API working by making
the old stream-oriented services delegate to the same typed operations. Do not
route the new Netty path through those stream methods.

**Step 4: Write and run typed operation tests**

Cover:

- empty and populated ref snapshots;
- prefix-filtered ls-refs;
- fetch pack creation from a commit want;
- receive of a valid pack and atomic ref creation;
- stale ref rejection without object-store mutation;
- pack buffer reader index remains unchanged.

Run:

```bash
mvn test -Pdev -q -pl core/git-native-storage -am \
  -Dtest=InMemoryNativeGitRepositoryProviderTest,NativeRepositoryOperationsTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/git-native-storage
git commit -m "Add in-memory native Git repositories"
```

### Task 3: Define the server wire action boundary

**Files:**
- Create:
  `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitServerAction.java`
- Create:
  `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitServerWireMachine.java`
- Create:
  `net/git-transport/src/test/java/pro/deta/orion/transport/git/netty/GitServerWireMachineTest.java`
- Modify: `net/git-transport/pom.xml`

**Step 1: Write the failing central-ownership test**

Construct the machine with an allocator and
`InMemoryNativeGitRepositoryProvider`. Feed a fragmented initial upload-pack
request and inspect actions:

```text
Read
Write(protocol-v2 capability advertisement)
Read
```

Assert that repository `demo` was created and that the machine's observable
continuation changed from initial request to upload request without an external
phase variable.

Reflection-check `GitServerWireMachine` for exactly one
`ContinuationRuntime`-owned durable phase and reject fields named
`phase`, `state`, `accumulator`, or `sectionBuffer` outside continuation
objects.

**Step 2: Run the test and verify RED**

```bash
mvn test -Pdev -q -pl net/git-transport -am \
  -Dtest=GitServerWireMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the machine and actions do not exist.

**Step 3: Implement the minimal action contract**

Use:

```java
public sealed interface GitServerAction {
    record Read() implements GitServerAction {}
    record Write(ByteBuf chunk, boolean flush) implements GitServerAction {}
    record Close() implements GitServerAction {}
}
```

`Write` rejects null or unreadable buffers and owns the supplied reference.
`GitServerWireMachine` provides:

```java
GitServerAction action();
boolean accept(ByteBuf input);
void written();
void writeFailed(Throwable failure);
void endOfInput();
void close();
```

Its continuation classes own pending output and nested
`GitMinimalWireMachine` instances. Terminal close releases pending output and
partial parser state.

The machine normalizes `/demo.git` to `demo`, rejects blank names and `.`/`..`
segments, and always calls the permissive provider's `findOrCreate`.

**Step 4: Implement upload-pack discovery and fetch phases**

The upload path:

```text
InitialRequest
 -> WriteCapabilities
 -> ParseProtocolV2Request
 -> WriteLsRefs -> ParseProtocolV2Request
 -> WriteFetch -> Complete
```

Add a semantic `GitMinimalWireMachine` factory for protocol v2 requests if the
existing `GitProtocolV2SectionParser` is still whole-buffer-only. The server
machine must not concatenate pkt-line headers or duplicate wire parsing.

Encode responses with `GitPktLineWriter` and `GitSideBandWriter`. Call only
typed `NativeRepositoryOperations`.

**Step 5: Run upload machine tests and verify GREEN**

Cover fragmented initial request, ls-refs, fetch, malformed request, unknown
command, buffer ownership, and close during every nonterminal phase.

Run the command from Step 2.

**Step 6: Commit**

```bash
git add net/git-transport core/git-parser
git commit -m "Add central native Git server wire machine"
```

### Task 4: Add receive-pack phases and bounded pack completion

**Files:**
- Modify:
  `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitServerWireMachine.java`
- Create:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/StreamingPackReceiver.java`
- Modify:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackParseException.java`
- Modify:
  `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackIngestor.java`
- Create:
  `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack/StreamingPackReceiverTest.java`
- Modify:
  `net/git-transport/src/test/java/pro/deta/orion/transport/git/netty/GitServerWireMachineTest.java`

**Step 1: Write failing streaming pack tests**

Feed a valid pack one fragment at a time. Assert that the receiver distinguishes:

- incomplete input: await another fragment;
- complete pack: return one owned complete buffer/result;
- malformed pack: fail immediately;
- bytes beyond the pack checksum: reject;
- configured maximum size: reject and release accumulated fragments;
- close before completion: release all fragments.

The first implementation may retain one bounded `CompositeByteBuf` because the
ephemeral backend's current `PackIngestor` validates a complete pack. It must
not create a second full byte-array copy in the transport layer.

**Step 2: Run the receiver test and verify RED**

```bash
mvn test -Pdev -q -pl core/git-native-storage -am \
  -Dtest=StreamingPackReceiverTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `StreamingPackReceiver` does not exist.

**Step 3: Implement bounded pack completion**

Give `PackParseException` a typed kind:

```java
INCOMPLETE, MALFORMED, LIMIT_EXCEEDED
```

Refactor `PackIngestor` so truncated headers, objects, deflate streams, and
checksums report `INCOMPLETE`, while invalid values report `MALFORMED`.
`StreamingPackReceiver` owns retained components and retries validation only
when new data arrives. It transfers the completed composite exactly once.

**Step 4: Add receive-pack wire tests**

Cover:

- receive advertisement;
- one ref creation command with `report-status`;
- fragmented raw pack;
- successful report status;
- malformed pack;
- stale old id;
- disconnect before pack completion;
- delete command with no pack.

**Step 5: Implement receive phases**

Use:

```text
InitialRequest
 -> WriteReceiveAdvertisement
 -> ParseReceiveCommands
 -> ReceivePack (only when required)
 -> ApplyReceive
 -> WriteReportStatus
 -> Complete
```

`GitServerWireMachine` owns command framing, capability selection, side-band,
and report-status encoding. It delegates only the typed commands and completed
pack to `NativeRepositoryOperations.receive`.

**Step 6: Run both focused test suites and verify GREEN**

```bash
mvn test -Pdev -q -pl net/git-transport -am \
  -Dtest=GitServerWireMachineTest,StreamingPackReceiverTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 7: Commit**

```bash
git add core/git-native-storage net/git-transport
git commit -m "Serve native receive-pack through wire machine"
```

### Task 5: Reduce the Netty adapter to a transport bridge

**Files:**
- Modify:
  `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitNativeProtocolAdapter.java`
- Modify:
  `net/git-transport/src/test/java/pro/deta/orion/transport/git/netty/GitNativeProtocolAdapterTest.java`
- Modify:
  `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitNettyTransportService.java`
- Create:
  `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitNettyTransportStateMachine.java`
- Modify:
  `net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java`
- Modify relevant tests under:
  `net/transport/src/test/java/pro/deta/orion/transport`

**Step 1: Write failing adapter bridge tests**

Using `EmbeddedChannel`, verify:

- `channelRead` passes the original readable bytes to the wire machine once;
- the input is released exactly according to `machine.accept`;
- a write action is submitted and followed by `machine.written()` only after
  successful channel write;
- failed writes call `machine.writeFailed`;
- `channelInactive` calls `endOfInput` and closes the machine;
- no adapter field stores Git protocol phase or accumulated payload.

**Step 2: Run adapter tests and verify RED**

```bash
mvn test -Pdev -q -pl net/git-transport -am \
  -Dtest=GitNativeProtocolAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: tests fail against the Phase 1 adapter.

**Step 3: Implement the thin adapter**

Delete the adapter's `Phase`, initial accumulator, parser calls, frame consumer,
payload consumer, and raw target factory. Construct exactly one
`GitServerWireMachine` for the connection and pump actions after every channel
event or completed write future.

Do not block an event-loop thread and do not call `.sync()` from the handler.

**Step 4: Wire the service and lifecycle**

`GitNettyTransportService` owns one singleton
`InMemoryNativeGitRepositoryProvider` and creates one machine per accepted
channel. Add `GitNettyTransportStateMachine` and replace the old blocking native
child in `TransportLifecycleStateMachine` for the `git-native` slot. Keep the
blocking classes present but inactive until their separate migration/removal.

Add `git-native-storage` as a production dependency of `git-transport`.

**Step 5: Run transport tests and verify GREEN**

```bash
mvn test -Pdev -q -pl net/transport -am \
  -Dtest=GitNativeProtocolAdapterTest,GitNettyTransportStateMachineTest,TransportLifecycleStateMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 6: Commit**

```bash
git add net/git-transport net/transport
git commit -m "Run native Git transport through Netty wire machine"
```

### Task 6: Prove real Git CLI push and clone

**Files:**
- Create:
  `net/git-transport/src/test/java/pro/deta/orion/transport/git/netty/GitNettyInMemoryServerCompatibilityTest.java`
- Modify: `TASKS.md`

**Step 1: Write the failing compatibility test**

Start `GitNettyTransportService` on `127.0.0.1:0`. In temporary directories:

1. initialize a source repository;
2. create and commit one file;
3. push `main` to `git://127.0.0.1:<port>/demo.git`;
4. clone that remote into another directory;
5. assert the file bytes and commit id;
6. stop and restart with a new provider and verify the repository is empty.

Use `ProcessBuilder` argument lists and bounded timeouts. Do not invoke a shell.

**Step 2: Run the test and verify RED**

```bash
mvn test -Pdev -q -pl net/git-transport -am \
  -Dtest=GitNettyInMemoryServerCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: the first unsupported or malformed server exchange fails.

**Step 3: Complete compatibility gaps**

Fix only behavior exposed by the Git CLI trace. Preserve WireMachine ownership:
no protocol parsing, phase, or accumulator may move back into the adapter.

**Step 4: Run focused and module verification**

```bash
mvn test -Pdev -q -pl net/git-transport -am \
  -Dtest=GitNettyInMemoryServerCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn verify -Pdev -q -pl net/transport -am
```

Expected: PASS.

**Step 5: Finish task tracking**

Mark the in-memory Netty server task complete and remove its owner line. Add a
short next task for authenticated repository resolution only if it is now the
next current implementation concern.

**Step 6: Commit**

```bash
git add net/git-transport TASKS.md
git commit -m "Complete in-memory native Git server"
```

### Task 7: Final verification

**Step 1: Check architecture boundaries**

Confirm:

- `GitNativeProtocolAdapter` contains no Git parsing or protocol phase;
- `GitServerWireMachine` owns the full initial-to-terminal conversation;
- the Netty path contains no `InputStream` or `OutputStream`;
- repositories, refs, and objects contain no Netty channel types;
- all `ByteBuf` allocations and retained slices have one release path;
- no outbound Git client/session type is used.

**Step 2: Run the full project test suite**

```bash
mvn test -Pdev
```

Expected: `BUILD SUCCESS`.

**Step 3: Review repository state**

```bash
git diff --check
git status --short
git log --oneline --decorate -8
```

Expected: no uncommitted implementation files and only the intended branch
commits ahead of `main`.

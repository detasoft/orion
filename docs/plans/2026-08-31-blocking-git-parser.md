# Blocking Git Parser Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the production Git parser continuation graph with a direct blocking parser path backed by timeout-aware `BufferedByteInput` and `BufferedByteOutput`.

**Architecture:** Introduce a direct blocking Git wire session API, cover every Git server flow with direct session tests, then move SSH/HTTP callers to it in one cutover that removes `GitByteBufTransportAdapter`. The blocking session reads pkt-lines from `BufferedByteInput`, writes responses through `GitNativeClientOutput`, and uses ordinary loops/method calls for v0/v1 and v2 protocol state.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Netty `ByteBuf`, Orion `BufferedByteInput`/`BufferedByteOutput`.

---

### Task 1: Timeout-Aware Queue Test I/O

**Files:**
- Create: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/QueueBufferedByteInput.java`
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/RecordingBufferedByteOutput.java`
- Test: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/QueueBufferedByteInputTest.java`

**Step 1: Write failing tests**

Add tests that prove a parser thread can block while the test thread feeds one
byte at a time:

```java
@Test
void readCopyWaitsForBytesFedFromAnotherThread() throws Exception {
    try (QueueBufferedByteInput input = new QueueBufferedByteInput(
            UnpooledByteBufAllocator.DEFAULT,
            Duration.ofSeconds(1))) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> result = executor.submit(() -> {
            ByteBuf copy = input.readCopy(3);
            try {
                return copy.toString(StandardCharsets.US_ASCII);
            } finally {
                copy.release();
            }
        });

        input.feed("a");
        input.feed("b");
        input.feed("c");

        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("abc");
        executor.shutdownNow();
    }
}
```

Also test timeout while waiting for the next byte:

```java
@Test
void readCopyFailsWhenTimeoutExpiresBeforeRequestedBytesArrive() throws Exception {
    try (QueueBufferedByteInput input = new QueueBufferedByteInput(
            UnpooledByteBufAllocator.DEFAULT,
            Duration.ofMillis(25))) {
        assertThatThrownBy(() -> input.readCopy(1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Timed out");
    }
}
```

**Step 2: Run tests to verify they fail**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=QueueBufferedByteInputTest
```

Expected: compile failure because `QueueBufferedByteInput` does not exist.

**Step 3: Implement test input**

Create `QueueBufferedByteInput` as a test helper implementing
`BufferedByteInput`. Use a monitor, an `ArrayDeque<Byte>`, a `closed` flag, and a
per-read deadline.

Core behavior:

```java
public void feed(byte[] bytes) {
    synchronized (lock) {
        for (byte value : bytes) {
            queue.addLast(value);
        }
        lock.notifyAll();
    }
}

private byte awaitByte() throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (lock) {
        while (queue.isEmpty()) {
            if (closed) {
                throw new EOFException("Queue input reached end of stream");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("Timed out waiting for input bytes");
            }
            TimeUnit.NANOSECONDS.timedWait(lock, remaining);
        }
        return queue.removeFirst();
    }
}
```

Add convenience `feed(String ascii)` and `end()` methods for later tests.

**Step 4: Run tests to verify they pass**

Run:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=QueueBufferedByteInputTest
```

Expected: pass.

**Step 5: Commit**

```bash
git add git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/QueueBufferedByteInput.java \
    git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/QueueBufferedByteInputTest.java \
    git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/RecordingBufferedByteOutput.java
git commit -m "Add blocking Git parser queue test IO"
```

---

### Task 2: Blocking Byte I/O Contracts

**Files:**
- Modify: `net/net-core/src/main/java/pro/deta/orion/net/io/BufferedByteInput.java`
- Modify: `net/net-core/src/main/java/pro/deta/orion/net/io/BufferedByteOutput.java`
- Test: existing net-core tests only

**Step 1: Update interface comments**

Document that implementations may block and must surface timeout/EOF as
`IOException`. Keep methods unchanged.

For `BufferedByteInput`, state:

```java
/**
 * Blocking buffered byte input.
 *
 * <p>Implementations may block while waiting for transport bytes. If configured
 * timeouts expire, read methods report that as {@link IOException}. EOF before
 * any byte is available may be reported as zero from {@link #readInto}; EOF
 * while satisfying exact reads must be reported as {@link java.io.EOFException}.
 */
```

For `BufferedByteOutput`, state:

```java
/**
 * Blocking buffered byte output.
 *
 * <p>Implementations may block until bytes are accepted by the transport or a
 * flush completes. Configured write or flush timeouts are reported as
 * {@link IOException}.
 */
```

**Step 2: Run focused tests**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl net/net-core
```

Expected: pass.

**Step 3: Commit**

```bash
git add net/net-core/src/main/java/pro/deta/orion/net/io/BufferedByteInput.java \
    net/net-core/src/main/java/pro/deta/orion/net/io/BufferedByteOutput.java
git commit -m "Document blocking byte IO timeout contracts"
```

---

### Task 3: Blocking Session Entry Point For Advertisement And V2 Ls-Refs

**Files:**
- Create: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Test: create `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java`

**Step 1: Write failing blocking fragmentation test**

Add a test that starts `GitBlockingWireSession.serveSmartHttpPost` on a parser
thread and feeds the v2
`ls-refs` request one byte at a time through `QueueBufferedByteInput`.

Expected assertion:

```java
assertThat(output.ascii())
        .contains(MAIN_ID + " HEAD symref-target:refs/heads/main")
        .contains(MAIN_ID + " refs/heads/main");
```

Also add a timeout test where only `000ecommand=ls` prefix is fed and the parser
thread fails with an `IOException` containing timeout text.

**Step 2: Run test to verify failure**

Run:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitBlockingWireSessionTest
```

Expected: compile failure because `GitBlockingWireSession` does not exist.

**Step 3: Implement skeleton**

Create `GitBlockingWireSession` as the public blocking parser entrypoint with:

```java
public final class GitBlockingWireSession {
    private final GitMinimalWireMachine.Context context;
    private final BufferedByteInput input;
    private final BufferedByteOutput output;

    void advertise(InitialRequestData data) throws IOException { ... }
    void serveCommand(InitialRequestData data) throws IOException { ... }
    void serveSmartHttpPost(InitialRequestData data) throws IOException { ... }
}
```

Give it a constructor that accepts the Git parser dependencies plus the concrete
input/output for the session:

```java
public GitBlockingWireSession(
        ByteBufAllocator allocator,
        NativeGitRepositoryProvider repositoryProvider,
        GitNativeRepositoryAccessHook accessHook,
        GitWireConfiguration configuration,
        NativePackfileUriSourceFactory packfileUriSourceFactory,
        BufferedByteInput input,
        BufferedByteOutput output) { ... }
```

For advertisement-only discovery, allow `input` to be `null` and fail only if a
method that needs request-body input is called.

For this task, support:

- v2 upload-pack advertisement via `context.clientOutput.sendV2UploadPackAdvertisement(...)`;
- smart HTTP POST command read;
- v2 `command=ls-refs`;
- `symrefs`, `peel`, `unborn`, and repeated `ref-prefix` arguments;
- response through `context.repositoryService.lsRefs(...)` and
  `context.clientOutput.sendLsRefs(...)`.

Use `GitBlockingWireTransport` as the pkt-line reader:

```java
GitPktLine packet = pkt.readPacket();
try {
    String payload = packet.payload().toString(StandardCharsets.US_ASCII);
    ...
} finally {
    packet.payload().release();
}
```

**Step 4: Keep production callers unchanged in this slice**

Do not route SSH or HTTP through `GitBlockingWireSession` until v2 fetch, legacy
upload-pack, and legacy receive-pack are implemented. This avoids a partial
production cutover while still avoiding a compatibility wrapper around
continuations.

**Step 5: Run focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitBlockingWireSessionTest
```

Expected: pass.

**Step 6: Commit**

```bash
git add git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java \
    git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java
git commit -m "Serve Git v2 ls-refs with blocking parser"
```

---

### Task 4: Protocol V2 Fetch

**Files:**
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Test: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java`
- Test: migrate relevant assertions from `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuationTest.java`

**Step 1: Write failing fetch tests**

Cover:

- valid `command=fetch` with `want`, `have`, `done`, and `thin-pack`;
- invalid fetch with no wants and no want-refs;
- fragmented one-byte feed through queue input;
- timeout in the middle of a fetch argument payload.

**Step 2: Implement v2 fetch parsing**

Move the request accumulation logic from `FetchContinuation` into ordinary
private methods on `GitBlockingWireSession` or a package-private helper.

Preserve these behavior rules:

- duplicate unsupported shallow/filter/ref-in-want/packfile-uri arguments mark
  the request invalid;
- `done=false` sends acknowledgments through
  `sendProtocolV2FetchAcknowledgments(...)`;
- `done=true` sends pack response through `beginProtocolV2Packfile(...)`;
- `sideband-all` controls v2 output framing when enabled.

**Step 3: Run focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitBlockingWireSessionTest,ProtocolV2PackfileResponseTest
```

Expected: pass.

**Step 4: Commit**

```bash
git add git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java \
    git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java
git commit -m "Serve Git v2 fetch with blocking parser"
```

---

### Task 5: Legacy Upload-Pack

**Files:**
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Test: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java`
- Test: migrate relevant assertions from `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadRequestContinuationTest.java`
- Test: migrate relevant assertions from `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadNegotiationContinuationTest.java`

**Step 1: Write failing legacy upload tests**

Cover:

- v1 advertisement then upload request;
- one-byte fragmented wants/haves/done negotiation;
- invalid object ID fails the session;
- `side-band-64k` response path.

**Step 2: Implement legacy upload parser**

Move request and negotiation state from legacy upload continuations into
ordinary parser methods.

Use existing exchange records:

- `LegacyUploadRequest`
- `LegacyUploadNegotiation`

Keep output through:

- `sendNak()`
- `sendAck(...)`
- `beginLegacySideBand64k(...)`
- `beginLegacyPack(...)`

**Step 3: Run focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitBlockingWireSessionTest
```

Expected: pass.

**Step 4: Commit**

```bash
git add git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java \
    git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java
git commit -m "Serve legacy upload-pack with blocking parser"
```

---

### Task 6: Legacy Receive-Pack And Pack Ingestion

**Files:**
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Test: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java`
- Test: migrate relevant assertions from `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceiveCommandContinuationTest.java`
- Test: migrate relevant assertions from `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackIngestionContinuationTest.java`

**Step 1: Write failing receive-pack tests**

Cover:

- valid command section followed by pack ingestion completion;
- one-byte fragmented command section;
- invalid command payload fails;
- timeout while pack bytes are incomplete;
- report-status output with and without `side-band-64k`.

**Step 2: Implement receive-pack parser**

Move command parsing from `ReceiveCommandContinuation` into blocking methods.
After command section boundary, create `PackIngestionSession` and run:

```java
while (true) {
    ByteBuf buffer = allocator.buffer(inputBufferSize, inputBufferSize);
    try {
        int read = input.readInto(buffer, inputBufferSize);
        if (read == 0) {
            throw new EOFException("Receive-pack input ended before pack completed");
        }
        switch (session.accept(buffer)) {
            case PackIngestionResult.NeedInput ignored -> { }
            case PackIngestionResult.Complete complete -> { ... return; }
            case PackIngestionResult.Failed failed -> throw new IOException(...);
        }
    } finally {
        buffer.release();
    }
}
```

On completion, call `completeLegacyReceivePack(...)` and send status through
`GitNativeClientOutput`.

**Step 3: Run focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitBlockingWireSessionTest
```

Expected: pass.

**Step 4: Commit**

```bash
git add git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java \
    git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java
git commit -m "Serve legacy receive-pack with blocking parser"
```

---

### Task 7: Cut Over Production Git Path And Remove Continuations

**Files:**
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitByteBufTransportAdapter.java`
- Delete or rewrite: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitByteBufTransportAdapterTest.java`
- Delete or deprecate unused files under `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java`
- Test: remove or rewrite continuation-specific tests under `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/`

**Step 1: Identify unused production classes**

Run:

```bash
rg -n "wire\\.continuation|GitMinimalWireMachine|Continuation<ByteBuf>|ContinuationFlow<ByteBuf>" git/git-parser/src/main/java net/http-core/src/main/java net/git-transport/src/main/java
```

Expected before cutover: `GitByteBufTransportAdapter`, `GitMinimalWireMachine`,
and exchange data records are still referenced. After cutover, only exchange
data records should remain useful. Move exchange records out of the
`continuation` package before deleting the continuation classes.

**Step 2: Move exchange records**

Move these records to `pro.deta.orion.git.parser.wire.exchange`:

- `InitialRequestData`
- `InitialRequestService`
- `LegacyReceiveCommand`
- `LegacyReceiveCommandSection`
- `LegacyReceivePack`
- `LegacyUploadNegotiation`
- `LegacyUploadRequest`
- `LsRefsRequest`

Update imports in parser, HTTP, SSH, and tests.

**Step 3: Delete unused continuation tests**

Remove tests whose only purpose is asserting continuation transitions. Preserve
behavior coverage in `GitBlockingWireSessionTest`.

**Step 4: Route SSH and HTTP directly through blocking session**

In `OrionGitRoute`, replace `GitByteBufTransportAdapter` construction with
direct `GitBlockingWireSession` construction for discovery and POST handling.

In `SshCommandFactory`, replace `GitByteBufTransportAdapter` construction with
direct `GitBlockingWireSession` construction for SSH command handling.

Delete `GitByteBufTransportAdapter` after no production or test callers import
it.

**Step 5: Run focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl git/git-parser,net/http-core,net/git-transport
```

Expected: pass.

**Step 6: Commit**

```bash
git add git/git-parser net/http-core net/git-transport
git commit -m "Remove production Git wire continuations"
```

---

### Task 8: Development Verification

**Files:**
- No planned source edits

**Step 1: Run routine development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: pass.

**Step 2: Fix failures caused by this branch**

If failures are from the blocking parser branch, fix them in the smallest
logical commit.

**Step 3: Leave unrelated failures alone**

If failures are caused by pre-existing or unrelated working tree changes, report
the failing module and relevant error output without modifying unrelated files.

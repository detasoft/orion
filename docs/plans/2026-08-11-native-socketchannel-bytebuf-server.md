# Native SocketChannel ByteBuf Server Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a separate native Git server backend that listens on its own port, handles each connection in a virtual thread, reads blocking `SocketChannel` data directly into Netty `ByteBuf` memory through `ByteBuf.nioBuffer()`, and drives `GitMinimalWireMachine`.

**Architecture:** Keep `ByteBuf` as the protocol memory model while replacing the Netty event-loop transport path with a blocking `ServerSocketChannel` listener. The server owns accept, connection lifecycle, read/write loops, and timeout handling; `GitMinimalWireMachine` remains the protocol engine and receives caller-owned `ByteBuf` chunks.

**Tech Stack:** Java 21 virtual threads, `java.nio.channels.ServerSocketChannel`, `java.nio.channels.SocketChannel`, Netty `ByteBuf`/`ByteBufAllocator`, JUnit 5, Maven `-Pdev`.

---

### Task 1: Add SocketChannel ByteBuf I/O Primitives

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/SocketChannelByteBufIO.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/SocketChannelByteBufIOTest.java`

**Step 1: Write the failing direct-read test**

Create a loopback `ServerSocketChannel`/`SocketChannel` pair in the test. Write `hello` from the client, call `SocketChannelByteBufIO.readInto(serverSideChannel, targetByteBuf, 8192)`, and assert:

```java
assertThat(read).isEqualTo(5);
assertThat(target.toString(StandardCharsets.US_ASCII)).isEqualTo("hello");
```

Also assert the `writerIndex` advanced by exactly the bytes read.

**Step 2: Run the focused test and verify RED**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=SocketChannelByteBufIOTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: test compilation fails because `SocketChannelByteBufIO` does not exist.

**Step 3: Implement direct read/write helpers**

Implement package `pro.deta.orion.git.parser.wire`:

```java
public final class SocketChannelByteBufIO {
    private SocketChannelByteBufIO() {
    }

    public static int readInto(
            SocketChannel channel,
            ByteBuf target,
            int minimumWritableBytes) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(target, "target");
        if (minimumWritableBytes <= 0) {
            throw new IllegalArgumentException(
                    "minimumWritableBytes must be positive");
        }
        target.ensureWritable(minimumWritableBytes);
        ByteBuffer destination = target.nioBuffer(
                target.writerIndex(),
                target.writableBytes());
        int read = channel.read(destination);
        if (read > 0) {
            target.writerIndex(target.writerIndex() + read);
        }
        return read;
    }

    public static int writeFrom(
            SocketChannel channel,
            ByteBuf source) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(source, "source");
        ByteBuffer data = source.nioBuffer(
                source.readerIndex(),
                source.readableBytes());
        int written = channel.write(data);
        if (written > 0) {
            source.skipBytes(written);
        }
        return written;
    }
}
```

**Step 4: Add partial-write coverage**

Add a test that writes a `ByteBuf` through `writeFrom(...)` into a loopback socket and verifies the client receives the same bytes. The helper should leave unread bytes in the source if the channel writes only part of the buffer.

**Step 5: Run focused tests and commit**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=SocketChannelByteBufIOTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Then commit:

```bash
git add \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/SocketChannelByteBufIO.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/SocketChannelByteBufIOTest.java
git commit -m "Add SocketChannel ByteBuf IO helpers"
```

### Task 2: Extract a Reusable Wire Machine Driver

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireMachineDriver.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitByteBufTransportAdapter.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireMachineDriverTest.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitByteBufTransportAdapterTest.java`

**Step 1: Write the failing driver test**

Create a fake `GitMinimalWireMachine`-style driver target is hard because `GitMinimalWireMachine` is final. Instead, write a behavior test around a real `GitMinimalWireMachine` using an in-memory repository provider and a `GitNativeClientWrite` that records written chunks. Feed a v1 `git-upload-pack` initial request `ByteBuf` and assert the driver writes an advertisement.

Expected failing point: `GitWireMachineDriver` does not exist.

**Step 2: Run focused parser tests and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitWireMachineDriverTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Step 3: Implement `GitWireMachineDriver`**

Move the existing flow handling from `GitByteBufTransportAdapter` into:

```java
public final class GitWireMachineDriver {
    public void drive(GitMinimalWireMachine machine, ByteBuf input)
            throws IOException;
    public void handleFlow(GitMinimalWireMachine machine, RuntimeFlow flow)
            throws IOException;
}
```

Keep ownership unchanged: `drive(...)` must release the caller-owned input `ByteBuf` in a `finally` block, exactly like the current adapter.

**Step 4: Use the driver from `GitByteBufTransportAdapter`**

Replace private `drive(...)`, `handleFlow(...)`, `runTask(...)`, and `terminalError(...)` copies with a `GitWireMachineDriver` field. Keep `InputStream` transport behavior unchanged.

**Step 5: Run focused parser tests and commit**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitWireMachineDriverTest,GitByteBufTransportAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Commit:

```bash
git add \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireMachineDriver.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitByteBufTransportAdapter.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireMachineDriverTest.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitByteBufTransportAdapterTest.java
git commit -m "Extract Git wire machine driver"
```

### Task 3: Add Blocking SocketChannel Git Server

**Files:**
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/SocketChannelGitNativeServer.java`
- Test: `net/git-transport/src/test/java/pro/deta/orion/transport/git/SocketChannelGitNativeServerTest.java`

**Step 1: Write the failing bind/lifecycle test**

Create the server with address `127.0.0.1`, port `0`, backlog `10`, `ByteBufAllocator.DEFAULT`, an `InMemoryNativeGitRepositoryProvider`, `GitNativeRepositoryAccessHook.ALLOW_ALL`, and `GitWireConfiguration.allSupported()`.

Assert:

```java
server.start();
assertThat(server.boundAddress()).isNotNull();
assertThat(server.isRunning()).isTrue();
server.stop();
assertThat(server.isRunning()).isFalse();
```

Expected: compile failure because `SocketChannelGitNativeServer` does not exist.

**Step 2: Run focused transport test and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest=SocketChannelGitNativeServerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Step 3: Implement listener skeleton**

Implement a final class with:

```java
public final class SocketChannelGitNativeServer implements AutoCloseable {
    public void start();
    public void stop();
    public InetSocketAddress boundAddress();
    public boolean isRunning();
}
```

Use:

```java
ServerSocketChannel.open();
server.configureBlocking(true);
server.bind(new InetSocketAddress(address, port), backlog);
Thread.ofVirtual().name("orion-git-socketchannel-accept-", 0)
        .start(this::acceptLoop);
```

Store accepted `SocketChannel`s in a concurrent set so `stop()` can close them. `stop()` must close the server channel first, then all accepted channels, then interrupt/join the accept thread if needed.

**Step 4: Run lifecycle test and commit**

Run the focused test. Commit:

```bash
git add \
  net/git-transport/src/main/java/pro/deta/orion/transport/git/SocketChannelGitNativeServer.java \
  net/git-transport/src/test/java/pro/deta/orion/transport/git/SocketChannelGitNativeServerTest.java
git commit -m "Add blocking SocketChannel Git server"
```

### Task 4: Drive GitMinimalWireMachine From SocketChannel

**Files:**
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/SocketChannelGitNativeServer.java`
- Test: `net/git-transport/src/test/java/pro/deta/orion/transport/git/SocketChannelGitNativeServerTest.java`

**Step 1: Add upload-pack advertisement test**

Start the server with an in-memory native repository provider containing `/project.git`. Connect with a plain `Socket`, write:

```text
git-upload-pack /project.git\0host=localhost\0
```

encoded as one pkt-line. Assert the response contains `capabilities^{}` or advertised refs, matching existing native transport tests.

**Step 2: Run focused test and verify RED**

Expected: listener accepts connections but does not yet drive the wire machine, so response is empty or times out.

**Step 3: Implement connection loop**

For each accepted `SocketChannel`, start a virtual thread:

```java
Thread.ofVirtual()
        .name("orion-git-socketchannel-client-", 0)
        .start(() -> handle(channel));
```

Inside `handle(...)`:

1. Configure blocking mode.
2. Create `GitNativeClientOutput` using a `SocketChannelByteBufWrite`.
3. Create `GitMinimalWireMachine` with the configured allocator, repository provider, access hook, configuration, and packfile URI source factory.
4. Allocate one reusable input `ByteBuf`.
5. Loop until terminal or EOF:
   - call `SocketChannelByteBufIO.readInto(channel, input, inputChunkBytes)`;
   - if read `< 0`, return;
   - pass retained/readable input to `GitWireMachineDriver.drive(...)`;
   - call `input.discardReadBytes()` after the driver returns.
6. Release all buffers and close channel in `finally`.

Use a fresh chunk buffer for each driver call if retaining a slice is simpler than proving reusable-buffer ownership. Prefer correctness over avoiding one allocation.

**Step 4: Implement `SocketChannelByteBufWrite`**

Create an inner `GitNativeClientWrite` implementation:

```java
private final class SocketChannelByteBufWrite implements GitNativeClientWrite {
    private final SocketChannel channel;

    public CompletionStage<Void> write(ByteBuf chunk) {
        try {
            while (chunk.isReadable()) {
                int written = SocketChannelByteBufIO.writeFrom(channel, chunk);
                if (written == 0) {
                    Thread.onSpinWait();
                }
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
```

Because the channel is blocking and the connection runs in a virtual thread, this write path may block without consuming a platform thread.

**Step 5: Run focused tests and commit**

Run:

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest=SocketChannelGitNativeServerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Commit:

```bash
git add \
  net/git-transport/src/main/java/pro/deta/orion/transport/git/SocketChannelGitNativeServer.java \
  net/git-transport/src/test/java/pro/deta/orion/transport/git/SocketChannelGitNativeServerTest.java
git commit -m "Drive native Git wire machine from SocketChannel"
```

### Task 5: Wire Server Behind a Separate Port

**Files:**
- Modify: `core/configuration/src/main/java/pro/deta/orion/config/schema/GitTransportConfig.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`
- Test: `core/configuration/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java`
- Test: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitNativeTransportServiceTest.java`

**Step 1: Add config shape tests**

Add configuration tests for optional SocketChannel native settings:

```text
git.nativeSocket.enabled
git.nativeSocket.address
git.nativeSocket.port
git.nativeSocket.backlog
```

If the project prefers avoiding new config shape now, use a system property for the implementation switch and reuse `GitTransportConfig` address/port in tests. Do not silently bind a second port without explicit config.

**Step 2: Run config tests and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/configuration -am \
  -Dtest=OrionConfigurationBootstrapShapeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

**Step 3: Implement config or property selection**

Recommended minimal implementation:

- keep existing Netty/native implementation as default;
- add system property value `socketchannel` to `orion.git.transport.implementation`;
- when property is `socketchannel`, `GitNativeTransportService` starts `SocketChannelGitNativeServer` instead of Netty;
- reuse existing `GitTransportConfig` address/port/backlog for now.

This satisfies a separate server implementation without adding permanent config schema before the approach is proven. Add permanent separate-port config in a later task if the server is accepted.

**Step 4: Add service integration test**

In `GitNativeTransportServiceTest`, set:

```java
System.setProperty(
        GitNativeTransportService.IMPLEMENTATION_PROPERTY,
        "socketchannel");
```

Start the service and assert:

- `boundAddress()` is not null;
- upload-pack request gets native advertisement;
- stop closes the listener.

**Step 5: Run focused service tests and commit**

Run:

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest=GitNativeTransportServiceTest,SocketChannelGitNativeServerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Commit:

```bash
git add \
  core/configuration/src/main/java/pro/deta/orion/config/schema/GitTransportConfig.java \
  core/configuration/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java \
  net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java \
  net/git-transport/src/main/java/pro/deta/orion/transport/git/SocketChannelGitNativeServer.java \
  net/git-transport/src/test/java/pro/deta/orion/transport/git/GitNativeTransportServiceTest.java \
  net/git-transport/src/test/java/pro/deta/orion/transport/git/SocketChannelGitNativeServerTest.java
git commit -m "Expose SocketChannel native Git transport"
```

### Task 6: Verification and Task Tracking

**Files:**
- Create or modify a child task under `docs/plans/current-work/` or `docs/plans/upcoming-work/` only after deciding where this work belongs.

**Step 1: Run focused verification**

Run:

```bash
mvn verify -Pdev -T 4 -pl core/git-parser,net/git-transport -am
```

Expected: `BUILD SUCCESS`.

**Step 2: Run routine development verification if requested before commit**

Run:

```bash
mvn verify -Pdev -T 4
```

Expected: `BUILD SUCCESS`.

**Step 3: Update task tree**

Add a concise task node if this work becomes part of the active queue, for example:

```text
docs/plans/current-work/socketchannel-native-git-server/TASK.md
```

Keep it high-level:

```markdown
# Add SocketChannel Native Git Server

Status: done

Add a blocking `SocketChannel` native Git server that reads directly into
`ByteBuf` and drives `GitMinimalWireMachine` from virtual threads.
```

Update only the immediate parent `TASK.md` checkbox. Do not touch unrelated task nodes.

**Step 4: Final status**

Run:

```bash
git status --short
git log -5 --oneline
```

Report committed changes, verification commands, and any unrelated pre-existing working tree changes.

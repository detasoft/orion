# Netty Transport Wire Machine — Phase 2

## Goal

Connect `GitNativeProtocolAdapter` to the native Git service layer so that a
real `git clone` or `git push` over the native Git protocol (`git://`) completes
end-to-end without JGit and without `InputStream`/`OutputStream`.

Phase 1 added the Netty TCP server (`GitNettyTransportService`) and the
per-connection adapter (`GitNativeProtocolAdapter`) that feeds raw `ByteBuf`
chunks into `GitMinimalWireMachine`.  Phase 2 fills in the four stubs left
intentionally empty and wires the service into the DI lifecycle.

## Current State

`GitNativeProtocolAdapter` has four no-op stubs:

```java
// handleInitial — Phase 2: resolve repo via repoLookup, write real advertisement
machine = new GitMinimalWireMachine(alloc, frameConsumer, payloadConsumer, rawTargetFactory);

// frameConsumer — Phase 2: dispatch to service when section complete
private final FrameConsumer frameConsumer = (control, flow) -> { };

// payloadConsumer — Phase 2: accumulate into section buffer
private final StructuredPayloadConsumer payloadConsumer =
    (control, payload) -> { payload.release(); };

// rawTargetFactory — Phase 2: PackIngestor
private final RawTargetFactory rawTargetFactory =
    control -> buf -> buf.release();
```

`GitNettyTransportService` accepts a `Function<String, GitRepository> repoLookup`
placeholder but never calls it.

No `GitNettyTransportStateMachine` or Dagger binding exists yet.

## Non-Goals

Do not change the existing `GitNativeTransportService` (blocking sockets) or its
tests.

Do not add TLS or authentication to the native Git transport in this phase.

Do not migrate SSH or HTTP Git transports.

## Scope

### 1  Repository resolution and advertisement

In `handleInitial`, after `GitInitialServiceRequestParser.read()` succeeds:

1. Extract `request.repositoryPath()`, strip the leading `/` and `.git` suffix.
2. Call `repoLookup` (now backed by `GitRepositoryProvider`) to resolve the repo.
3. Build the advertisement `ByteBuf` using the native upload-pack or
   receive-pack advertisement writer — no `OutputStream`.
4. `ctx.writeAndFlush(advertisementBuf)`.
5. Transition to `Phase.SERVING`.

Error cases: repository not found → send Git error pkt-line, close channel.

### 2  FrameConsumer — section dispatch

`FrameConsumer` fires after each delimiter, flush, or response-end frame.
In Phase 2 it must detect which section just completed and dispatch to the
appropriate native service:

- upload-pack: after want/have negotiation is complete, trigger pack negotiation.
- receive-pack: after the command list + pack data are complete, trigger ref
  update.

The service call must write its response as `ByteBuf` via
`ctx.write(ByteBuf)` / `ctx.writeAndFlush(ByteBuf)`.  No streams cross
this boundary.

### 3  PayloadConsumer — section buffer accumulation

`PayloadConsumer` fires for each data pkt-line payload `ByteBuf`.  In Phase 2
it accumulates payloads into a per-section `CompositeByteBuf` so the
`FrameConsumer` can hand a complete section to the service layer.

Release the accumulated buffer after dispatch.

### 4  RawTargetFactory — PackIngestor target

`RawTargetFactory` is called when `GitMinimalWireMachine` enters raw-forwarding
mode (pack data stream).  In Phase 2 it must:

1. Create a `PackIngestor` (from `git-native-storage`) for the target repo.
2. Return a `RawSink.Target` that feeds each `ByteBuf` to the ingestor.
3. On `close()`, finalize the ingestor and signal completion to the service layer.

### 5  DI / lifecycle

Add `GitNettyTransportStateMachine` (mirrors `GitNativeTransportStateMachine`):

```java
@Singleton
public final class GitNettyTransportStateMachine
        extends ServiceLifecycleStateMachineAdapter {
    @Inject
    public GitNettyTransportStateMachine(Provider<GitNettyTransportService> serviceProvider) {
        super("git-netty", serviceProvider);
    }
}
```

Replace `Function<String, GitRepository> repoLookup` in
`GitNettyTransportService` with `Provider<GitRepositoryProvider>`.

Bind both classes in the Dagger component.

### 6  git-native-storage dependency

Add to `net/git-transport/pom.xml`:

```xml
<dependency>
    <groupId>pro.deta.orion.core</groupId>
    <artifactId>git-native-storage</artifactId>
</dependency>
```

## Files to modify

| File | Change |
|---|---|
| `net/git-transport/pom.xml` | add `git-native-storage` dependency |
| `GitNativeProtocolAdapter` | fill in four stubs; inject `GitRepositoryProvider` |
| `GitNettyTransportService` | replace `repoLookup` with `Provider<GitRepositoryProvider>` |

## Files to create

| File | Purpose |
|---|---|
| `netty/GitNettyTransportStateMachine.java` | lifecycle adapter, Dagger `@Singleton` |
| `netty/GitNativeProtocolAdapterTest` (extend) | integration tests with embedded channel + stub repo |

## Verification

```
mvn test -pl net/git-transport -Dtest=GitNativeProtocolAdapterTest
```

Integration test (new):

```
git clone git://127.0.0.1:{port}/repo.git   # upload-pack path
git push git://127.0.0.1:{port}/repo.git    # receive-pack path
```

Both must complete without JGit and without `InputStream`/`OutputStream` on the
hot path.

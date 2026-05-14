# Native Git Transport NIO Migration

## Goal

Decide whether Orion should migrate the native Git transport from classic
blocking sockets to a NIO-based implementation, then introduce the migration
only if it improves concurrency, shutdown behavior, or resource control without
changing Git protocol behavior.

The first target is the native Git transport in `net/git-transport`, not SSH or
HTTP Git access.

## Current State

`GitNativeTransportService` listens with `ServerSocket`, accepts `Socket`
connections on a dedicated accept thread, and submits each connection to
`OrionExecutor`.

Each accepted connection is checked with `ConnectionAccessRules.localOnly()`,
gets a request id, receives a five second socket timeout, and is adapted to
`StandardStreams` through `Socket.getInputStream()` and
`Socket.getOutputStream()`.

`GitInternalService` owns command parsing and dispatch. It reads the initial
Git pkt-line command, opens the target repository, checks repository access, and
delegates to `GitRepository.upload()` or `GitRepository.receive()`.

`JGitRepository` is still the concrete repository implementation for protocol
serving. It delegates upload and receive flows to JGit `UploadPack` and
`ReceivePack`, both of which use blocking `InputStream` and `OutputStream`.

This means a simple NIO listener can improve accept/shutdown structure, but it
will not become fully non-blocking while protocol serving remains behind the
blocking stream boundary.

## Non-Goals

Do not rewrite Git pack negotiation as part of this migration.

Do not replace JGit `UploadPack` or `ReceivePack` in the first NIO step.

Do not change repository authorization semantics.

Do not expose the native Git transport beyond the current local-only access
model unless a separate security plan explicitly changes that.

Do not migrate SSH or HTTP Git transports in the same change.

## Scope

Extract the listener lifecycle from `GitNativeTransportService` enough to allow
more than one native transport engine behind the same service-level behavior.

Add a compatibility NIO engine behind configuration. This engine may start with
`ServerSocketChannel` and `SocketChannel` adapted back to streams, because that
keeps the first change behaviorally small.

Keep the classic socket engine as the default until the NIO engine passes the
same functional tests and a concurrency comparison.

Define the resource model explicitly: accept loop ownership, per-connection
worker execution, connection timeout, maximum accepted connections, shutdown
order, and error logging.

Evaluate Java 21 virtual threads as an alternative to true selector-driven NIO
before committing to a larger non-blocking protocol layer.

## Phased Plan

Phase 1: Baseline the existing transport.

Capture the current behavior for startup, shutdown, local-only rejection,
command parsing failures, clone/fetch, push, socket timeout, interrupted
clients, and concurrent clients.

Phase 2: Introduce a native transport engine boundary.

Keep `GitNativeTransportService` responsible for lifecycle integration,
configuration, `GitInternalService`, and security context creation. Move the
socket accept implementation behind a small internal engine so classic sockets
and NIO can be selected without duplicating Git command handling.

Phase 3: Add NIO stream-compatibility mode.

Implement a `ServerSocketChannel` based engine that preserves the existing
blocking stream contract when it invokes `GitInternalService`. This proves the
lifecycle boundary and gives an incremental migration point without changing
JGit protocol behavior.

Phase 4: Add resource controls.

Make connection limits, accept failure behavior, timeout application, and
shutdown of active connections explicit. The NIO engine should not be accepted
only because it uses different classes; it must make operational behavior
clearer than the current implementation.

Phase 5: Measure the result.

Compare classic sockets, NIO stream-compatibility mode, and virtual-thread
serving if practical. Use clone/fetch/push workloads, idle clients, slow
clients, and concurrent local clients.

Phase 6: Decide on true non-blocking work.

If JGit remains the protocol implementation, a selector-driven transport still
needs to hand each active Git operation to blocking workers. A true non-blocking
implementation should wait until Orion owns enough Git protocol and pack
serving logic to avoid immediately converting channels back into streams.

## Open Questions

What concrete problem should NIO solve first: connection scale, shutdown
reliability, timeout control, memory use, or cleaner lifecycle code?

Should the first implementation be selected by a temporary feature flag, a
stable config property, or a test-only configuration path?

Is virtual-thread-per-connection a better Java 21 target than selector-driven
NIO while JGit still requires blocking streams?

Should native Git transport remain loopback-only permanently, or is external
native Git access expected later?

What is the minimum benchmark that proves this work is worth keeping?

## Verification

Cover the existing behavior before adding the new engine:

- startup binds the configured address and port;
- shutdown closes the listener and stops accepting new clients;
- non-loopback clients are rejected by connection access rules;
- malformed initial Git commands do not crash the listener;
- clone or fetch works for an existing repository;
- push works for an existing repository and for allowed repository creation;
- missing repository fetch does not create the repository;
- slow or idle clients hit the configured timeout;
- interrupted clients close without leaking active connection state;
- multiple concurrent clients can complete without blocking accept shutdown.

The NIO engine should run through the same tests as the classic socket engine.
Only after that should performance or concurrency measurements decide whether
NIO becomes the default.

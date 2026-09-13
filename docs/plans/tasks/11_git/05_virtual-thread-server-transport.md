# Complete Virtual-Thread Execution for Git Server Transports

Status: todo
Depends on: [Git server transport composition review](04_server-transport-architecture-review.md)

## Required Result

Run each complete Git server command on a virtual thread across native TCP,
SSH, and Smart HTTP. Preserve the shared blocking protocol path and make
cancellation, backpressure, and resource ownership explicit at transport
boundaries. Blocking repository access must not occupy shared platform workers.

## Current Model

Source inspection on 2026-09-13 establishes the following baseline; this is not
runtime verification or completion evidence:

- `net/net-core` already provides `BufferedByteInput`, `BufferedByteOutput`, and their
  stream adapters. Do not introduce replacement byte contracts.
- `git/git-parser` already provides `GitWireBootstrap`,
  `GitBlockingWireSession`, and `GitBlockingWireTransport`, used by the native
  TCP, SSH, and HTTP server paths. Do not reimplement the protocol runner.
- `GitNativeTransportService.startConnection` already starts a virtual thread
  per connection and tracks sockets for shutdown. Its accept loop uses a
  platform thread; listener infrastructure is distinct from command execution.
- `SshCommandFactory.GitSshCommand.start` submits the complete blocking Git command
  to `OrionExecutor`. That executor uses platform threads from
  `OrionThreadFactory`, so slow Git sessions consume shared workers.
- `OrionGitRoute` performs discovery and POST processing synchronously.
  `JettyHTTPServer` constructs a `QueuedThreadPool(10, 2, 120)` without virtual
  thread configuration. The production dispatch path needs migration and a
  runtime test, not only direct route tests.
- `GitBlockingClientExecutor` already starts a virtual thread per remote client
  operation. Client simplification remains in its existing task subtree.
- Resumable output wrappers still have their own
  [blocking output migration task](01_wire-architecture-simplification/02_blocking-output-migration.md).
  Reuse its result instead of duplicating that work here.

## Dependencies and Order

The transport composition review remains the implementation prerequisite,
including its wire, client, and remote-bootstrap dependencies. An existing
blocking runner alone does not satisfy that review or its acceptance criteria.
Apply its accepted ownership decisions when implementing this task.

Complete this transport foundation before extending command execution to slow
external storage where practical. The
[externalized repository storage task](06_externalized-repository-storage.md)
has its own storage-review prerequisite; S3 is not a prerequisite here and no
new hard dependency is inferred from numeric order.

Virtual threads and stateless deployment solve separate requirements. This task
covers execution and lifecycle. Durable objects, refs, metadata, multi-instance
coordination, and restart from shared storage belong to externalized storage.

## Design and Scope

- Keep one virtual thread responsible for each TCP session, SSH Git command,
  or HTTP Git request, including repository resolution and pack streaming.
  Retain already-correct native TCP behavior.
- Move SSH Git execution off `OrionExecutor` without converting unrelated SSH
  operator commands or replacing the global executor.
- Use the smallest supported Jetty dispatch mechanism that runs Git application
  work on virtual threads and preserves servlet/filter and exchange lifetime.
  Establish the mechanism against the repository's actual Jetty version.
  If shared Jetty configuration changes other routes, verify those consumers.
- Framework selectors, acceptors, and shared deadline schedulers may remain
  platform threads. Do not create a virtual thread per read/write or force
  framework networking internals onto virtual threads.
- Give each command/request one lifecycle owner for its worker, streams,
  repository resources, pack producer, cancellation, and terminal completion.
  Disconnect, command destruction, timeout, and server stop must unblock I/O
  and terminate work within a tested bound, without duplicate SSH exit or HTTP
  completion signals.
- Preserve bounded buffering and blocking backpressure. Do not add an unbounded
  queue or callback pump unless the actual transport API requires bridging;
  any required bridge must have bounded capacity and explicit close ownership.
- Preserve authentication context, authorization, repository resolution,
  receive-pack publication, upload-pack, side-band, protocol v2, HTTP error
  mapping, and packfile-URI delivery. Preserve buffer ownership across workers.
- Reuse existing timeout mechanisms. Enforce supported deadlines at the
  transport boundary or with shared cancellation that closes the transport;
  never allocate a thread per I/O call to implement a timeout.
- Update all real consumers of any replaced internal Git execution path and
  remove that path in the same change. Retain service lifecycle adapters when
  they still own server startup/shutdown; their names do not prove obsolete
  continuation logic.

## Implementation Plan

1. Trace the reviewed production entrypoints and existing lifecycle tests.
   Record where each complete command starts, blocks, completes, and closes;
   identify any shared Jetty consumers affected by the chosen dispatch change.
2. Extend transport tests to observe the executing thread at the repository
   boundary and characterize success, cancellation, and bounded shutdown.
3. Move SSH Git commands to owned virtual-thread execution; wire destruction
   and shutdown to cancellation and stream closure, preserving exit semantics.
4. Move HTTP discovery, upload-pack, receive-pack, and Git packfile delivery to
   the reviewed virtual-thread dispatch boundary. Preserve response lifetime,
   authorization context, streaming, and error translation.
5. Retain native TCP's existing virtual-thread runner and verify its parity and
   shutdown behavior. Remove only superseded Git execution glue and its callers.
6. Exercise all three production transports with slow and concurrent clients;
   resolve lifecycle and buffering failures before declaring the migration done.

## Acceptance and Verification

- Runtime tests observe `Thread.currentThread().isVirtual()` during repository
  work through native TCP, SSH, and real Jetty dispatch, including discovery
  and pack transfer. Direct parser or route calls alone are insufficient.
- Existing successful push/fetch/clone, side-band, protocol-v2, authentication,
  authorization, and HTTP mapping behavior remains covered.
- A slow reader and a slow writer do not starve an independent Git operation;
  transfers larger than adapter buffers preserve bytes and bounded buffering.
- Peer disconnect, SSH destroy, and server stop during blocked input/output
  terminate the worker and release resources within deterministic test bounds.
- Timeout tests verify the advertised deadline and underlying transport closure;
  failed pack delivery closes its producer and does not report success.
- Concurrent sessions retain separate security contexts, completion signals,
  and repository resources. Stop/start does not retain old session workers.
- Extend the existing tests in `net/git-transport`, `net/http-core`, and
  `git/git-parser` where their responsibilities apply. Use real transport
  fixtures for dispatch and disconnect behavior, and the Git interoperability
  fixtures for protocol parity. Run focused tests through `make run-test`;
  run the full Maven/JVM suite for implementation verification. Explicitly
  execute any required real-Jetty integration fixture and record its result.
- No duplicate byte API, protocol runner, global executor replacement, or
  alternate legacy Git execution mode is introduced.

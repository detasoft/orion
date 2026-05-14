# Native Git Transport Baseline Tests

## Goal

Add focused baseline tests for the current blocking-socket native Git transport
before introducing a NIO transport engine.

The tests should capture observable behavior of `GitNativeTransportService`:
startup, shutdown, socket handling, command dispatch, timeout behavior,
repository access, and failure handling. Once these tests exist, the same
behavior can be reused as the compatibility suite for a future NIO engine.

## Current State

`GitNativeTransportService` currently:

- reads `transport.git` configuration from `ConfigurationContext`;
- creates a `ServerSocket` with configured address, port, and backlog;
- starts an accept loop on a dedicated thread from `OrionExecutor`;
- submits every accepted `Socket` to `OrionExecutor`;
- rejects non-local clients through `ConnectionAccessRules.localOnly()`;
- sets a five second socket timeout;
- adapts socket streams to `StandardStreams`;
- calls `GitInternalService.service()` with `GitInternalService::parse`;
- closes the socket in `finally`;
- closes the listener socket during `onStop()`.

Most Git protocol behavior is already covered below the network layer by
`GitInternalServiceProtocolTest` and shared `Scenarios`. SSH has end-to-end
coverage through `GitSshTransportEndToEndIT`. The native Git socket service
itself does not have equally explicit baseline coverage.

## Non-Goals

Do not introduce NIO in this step.

Do not rewrite Git protocol parsing or pack handling.

Do not replace JGit `UploadPack` or `ReceivePack`.

Do not require tests to bind to the default port `9418`.

Do not depend on external network interfaces or public DNS.

## Test Strategy

Use three layers of tests.

Layer 1: lifecycle and socket behavior.

These tests should instantiate `GitNativeTransportService` with controlled
configuration and a lightweight `GitInternalService` test double. They should
verify binding, accepting, dispatching, timeout, malformed input handling, and
shutdown without needing full Git repository setup.

Layer 2: native transport protocol smoke tests.

These tests should run real protocol transcripts through the native socket
transport and reuse existing `Scenarios` where possible. The goal is not to
duplicate every `GitInternalServiceProtocolTest`, but to prove that the native
socket adapter does not alter command parsing, stream ordering, or error
responses.

Layer 3: optional end-to-end Git client test.

Use a real Git client or JGit client against `git://localhost:<port>/<repo>`
only for one high-value happy path if it is stable enough in local and CI
environments. Keep the main baseline independent from an external Git binary.

## Test Harness Requirements

The test harness should avoid fixed ports. It can either reserve an available
loopback port before starting the service or allow port `0` and expose the
actual bound port for tests.

If `GitNativeTransportService` does not expose enough state to test cleanly,
prefer a small testable seam:

- a package-private constructor accepting `GitTransportConfig` directly; or
- a package-private listener/engine object returned by the service; or
- a package-private accessor for the bound local socket address.

Keep the seam narrow and avoid designing the future NIO abstraction in this
baseline step.

Tests must stop the service in `@AfterEach` and close all client sockets. The
accept loop should not leave background threads running after test completion.

## Phased Plan

Phase 1: Add lifecycle tests with a test double.

Create tests in `net/git-transport` for disabled transport, enabled transport
binding, one accepted local connection, listener close on stop, and connection
close after handling.

Phase 2: Add malformed and idle client tests.

Connect to the service and send empty input, malformed pkt-line input, or no
input. Verify the listener remains alive for later clients and idle clients are
closed according to the configured socket timeout behavior.

Phase 3: Add command dispatch assertions.

Use a `GitInternalService` test double that records client id, request id, and
received bytes. Verify the service passes socket streams to the Git service and
creates a nonblank request id per connection.

Phase 4: Add protocol transcript smoke tests.

Back the service with a real `GitInternalService`, `FileGitRepositoryProvider`,
and recording event manager. Run a small subset of existing scenarios through a
real socket:

- receive-pack creates a repository before accepting the first push;
- upload-pack fetches an existing branch;
- upload-pack for a missing repository does not create it.

Phase 5: Add concurrency and shutdown tests.

Open several clients concurrently, verify they are submitted independently, and
verify `onStop()` prevents new accepts without hanging active test cleanup.

Phase 6: Mark baseline as reusable.

Structure assertions so a future NIO engine can run the same behavioral suite.
The suite should name the transport implementation under test, but the expected
behavior should not depend on `ServerSocket` classes.

## Recommended Test Cases

Start with these concrete tests:

- disabled transport does not bind or accept connections;
- enabled transport binds loopback address and selected test port;
- one local client connection reaches the Git service;
- each connection gets a distinct nonblank request id;
- malformed initial command does not stop the listener;
- missing repository fetch closes cleanly and does not create the repository;
- receive-pack can create a repository when the user has create permission;
- shutdown closes the listener and rejects new connections;
- stopping before start is harmless;
- slow or idle client is closed by timeout;
- concurrent clients do not block listener shutdown.

## Open Questions

Should the socket timeout become configurable before testing idle clients, or
should tests tolerate the current five second timeout?

Should native transport tests live in `net/git-transport` as unit-style tests,
or in `tests/integration-test` as runtime end-to-end tests?

Should baseline tests use raw protocol transcripts only, or include one real
Git/JGit client scenario?

How much internal state should `GitNativeTransportService` expose for tests
before the transport engine boundary exists?

Can non-loopback rejection be tested reliably in CI, or should that remain
covered by `ConnectionAccessRules` unit tests until a network namespace based
test exists?

## Verification

The baseline is complete when:

- `net/git-transport` has tests for lifecycle, socket dispatch, malformed
  input, timeout, and shutdown;
- at least one real Git protocol receive path and one upload path pass through
  the native socket service;
- tests do not bind to fixed port `9418`;
- tests leave no listening sockets or active transport threads behind;
- the same expected behavior can be reused for a future NIO implementation;
- routine `mvn test -Pdev` runs the baseline suite without external services.

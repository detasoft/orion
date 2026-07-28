# Native Git In-Memory Server Design

## Goal

Run the native `git://` Netty server against an ephemeral in-memory repository
backend so real Git CLI clone and push operations exercise the native wire and
storage paths without depending on JGit or committing to a production
repository format.

The first server profile is intentionally permissive. It performs no
authentication or authorization, and any valid repository path is created on
first access. Repository contents live only for the lifetime of the process.

## Architecture

One `GitServerWireMachine` owns the complete protocol conversation for each
accepted connection:

```text
Netty Channel
      |
GitNativeProtocolAdapter
      |
GitServerWireMachine
      |
InMemoryNativeGitRepositoryProvider
      |
InMemoryNativeGitRepository
      |
LooseRefStore + LooseObjectStore
```

`GitNativeProtocolAdapter` is a transport bridge. It forwards inbound
`ByteBuf`s, executes outbound writes requested by the wire machine, reports
channel closure and transport failures, and applies the machine's buffer
ownership decision. It does not parse Git messages or keep a second protocol
phase.

`GitServerWireMachine` is the central owner of Git protocol state. It composes
the continuation-based pkt-line and raw-stream machinery and advances through
the initial request, repository resolution, advertisement, service request,
pack transfer, response, and terminal phases.

`InMemoryNativeGitRepositoryProvider` owns the process-wide map from normalized
repository names to in-memory repositories. Each repository owns its refs,
objects, and HEAD target. The provider contains no connection or wire-protocol
state.

## Wire Machine Phases

The server machine starts before the initial service request and owns all
durable phases:

```text
InitialRequest
    -> ResolveRepository
    -> Advertisement
    -> UploadRequest
       -> LsRefsResponse -> UploadRequest
       -> FetchResponse -> Complete
    -> ReceiveCommands
       -> ReceivePack
       -> ReportStatus
       -> Complete
    -> Failed
```

The initial phase consumes a fragmented initial request without a parallel
accumulator in the Netty adapter. Repository resolution normalizes the path,
rejects malformed traversal-like names, and calls `findOrCreate`.

Upload and receive phases use the existing `GitMinimalWireMachine` framing
continuations and typed request parsers. The server machine does not parse
pkt-line headers or structured payloads a second time.

Outbound data is exposed as owned `ByteBuf` write actions. The Netty adapter
releases each action buffer after the channel accepts ownership or after a
failed write, according to one explicit contract.

## Repository Responsibilities

The in-memory provider stores:

- one `LooseRefStore` per repository;
- one `LooseObjectStore` per repository;
- the repository HEAD target;
- stable repository state shared by all connections in the same process.

Closing a per-connection repository handle does not delete its state. Stopping
the process discards all state.

The repository exposes typed domain operations rather than stream-oriented Git
sessions:

- snapshot advertised refs;
- resolve `ls-refs`;
- validate a fetch request and build a pack result;
- validate receive commands;
- ingest a supplied pack into quarantine;
- apply atomic ref updates;
- return a typed receive result.

The repository does not parse pkt-lines, write side-band frames, or encode
report-status.

## Existing Native Services

`NativeUploadPackService` currently owns an `InputStream`/`OutputStream`
conversation loop. That loop conflicts with the central server wire machine and
is not used by the Netty path. Its reusable repository operations are moved
behind typed in-memory repository methods or smaller operation classes.

`NativeReceivePackService` contains valuable repository behavior: policy
validation, quarantine, object validation, fast-forward checks, and atomic ref
updates. This behavior remains, but pkt-line advertisement and report-status
encoding move to the server wire machine. The resulting receive operation
accepts typed commands plus pack bytes and returns a typed result.

The existing blocking repository API remains available until its transport is
migrated. The Netty path must not adapt its hot path back through
`InputStream`/`OutputStream`.

## Permissive First Profile

The first profile has no `SecurityContext`, authentication, ACL lookup, or
authorization rules:

- every valid path can be read;
- every valid path can be written;
- a missing repository is created on first clone or push.

The provider and wiring are named as in-memory/permissive so this behavior is
not mistaken for a production security boundary. A later authenticated profile
will resolve a security context before repository creation and reuse the
`GitInternalService` READ, WRITE, and CREATE policy.

## Errors and Resource Ownership

Malformed initial requests, invalid pkt-lines, unsupported commands, invalid
packs, and rejected ref transactions become bounded Git protocol error or
report-status responses. Error text does not include repository contents or raw
request bytes.

Each connection closes:

- its current wire continuation;
- partial structured payload buffers;
- raw pack targets;
- pending outbound buffers not transferred to Netty;
- its repository handle.

The provider-owned refs and objects outlive individual connections.

## Testing

Repository tests cover:

- `findOrCreate` identity and multiple names;
- state visibility across separate handles;
- process-local deletion semantics;
- concurrent creation of the same name;
- typed fetch and receive operations.

Wire-machine tests feed fragmented `ByteBuf` input without Netty and cover:

- fragmented initial upload-pack and receive-pack requests;
- automatic repository creation;
- empty and populated advertisement;
- protocol v2 `ls-refs` and fetch;
- receive command plus raw pack;
- report-status success and rejection;
- malformed and truncated input;
- buffer release on success, failure, and close.

Netty adapter tests use `EmbeddedChannel` to verify it only bridges channel
events and machine actions.

Compatibility tests run real Git CLI operations against an ephemeral bound
port:

```text
git clone git://127.0.0.1:<port>/demo.git
git push git://127.0.0.1:<port>/demo.git
```

The pushed commit must be cloneable through a later connection to the same
in-memory repository.

## Scope

This work implements only the native unauthenticated `git://` server with
ephemeral repositories. It does not add persistence, authentication, ACLs,
TLS, SSH, smart HTTP, outbound Git clients, delta generation, or a finalized
repository disk format.

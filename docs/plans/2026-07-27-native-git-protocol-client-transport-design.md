# Native Git Protocol Client Transport Design

## Goal

Establish small JGit-free transport and repository boundaries for future native
remote Git fetch and push clients without implementing protocol commands, a
real network transport, or a concrete repository backend yet.

## Context

`core/git-parser` already owns binary-safe pkt-line writing, capability
handling, side-band processing, and report-status parsing. Client-side remote
work needs to compose those primitives around a connection, but connection
lifecycle and protocol parsing are separate responsibilities.

The repository also has active native clone and receive-pack tasks. This slice
must avoid their storage and server-side service code.

## Options Considered

### Dedicated protocol client module

Create `core/git-protocol-client` and let it depend on `git-parser`. This keeps
transport lifecycle separate from wire parsing, gives future fetch and push
clients a focused home, and makes the JGit-free dependency boundary explicit.

### Client package inside `git-parser`

This would require less Maven wiring, but it would mix stateful connection
lifecycle with reusable wire codecs. It would also make future HTTP and SSH
dependencies part of the parser module.

### Implement `git://` immediately

A real TCP transport would provide an early network test, but introduces socket
lifecycle, timeout, address, and failure-mapping decisions before the client
contract has been exercised.

The dedicated module is selected because it preserves the existing parser
boundary and provides the smallest useful foundation.

## Module Boundary

Add `core/git-protocol-client` to the core Maven reactor. Production code in the
module may depend on `git-common`, `git-parser`, and Netty buffer APIs exposed by
those modules, but it must not import JGit or declare a JGit dependency.

The module initially contains transport-facing contracts:

- `GitProtocolService` identifies upload-pack and receive-pack;
- `GitProtocolTransport` opens a session for a service and remote URI;
- `GitProtocolSession` exchanges `ByteBuf` chunks and closes explicitly;
- `GitProtocolTransportOptions` carries bounded connection settings needed by
  all later transports.

It also contains two independent repository ports:

- `GitRepositoryRefs` lists and resolves refs and performs compare-and-set ref
  updates;
- `GitRepositoryContents` reads and stores pack content without publishing
  refs.

No production transport or repository implementation is included in this
slice.

## Repository Ports

`GitRepositoryRefs` owns ref metadata but not object bytes. `listRefs` returns
the ref name, commit id, optional peeled id, and optional symbolic-ref target.
`resolveCommit` resolves one name. `updateRef` requires an expected old commit
id and a new commit id so concurrent writers cannot overwrite one another. Ref
creation uses an absent expected old id. The result distinguishes updated,
stale, missing-commit, and rejected outcomes.

`GitRepositoryContents` owns pack bytes but not ref publication. It opens a
chunked reader for an existing pack and a chunked writer for a new pack. A
writer publishes and returns the pack id only when `complete` succeeds; closing
an incomplete writer aborts it. Both ports are free of `Path`, `File`, `.git`
layout, and `InputStream` assumptions.

The orchestration order is deliberate:

1. stream and durably complete the pack through `GitRepositoryContents`;
2. confirm the new commit is available to the backend;
3. publish the ref through `GitRepositoryRefs.updateRef`.

If the ref compare-and-set fails, the pack remains unreachable and can be
removed later by maintenance. Refs must never point to content that has not
completed successfully.

## Data Flow and Ownership

A future protocol client asks a transport to open a session for one Git service.
It writes already-framed request chunks and reads response chunks through the
session. Pkt-line construction and parsing remain in `git-parser`.

The transport and content contracts use `ByteBuf` so binary packet and pack
data do not pass through text or `InputStream` adapters. Buffer ownership is
explicit: callers retain ownership of buffers passed to writers, while callers
own and must release buffers returned by readers.

Closing a session is idempotent. A client closes it on both successful and
failed operations.

## Scripted Test Transport

Test sources provide a scripted transport and session fixture. A script records
the requested service, URI, and options, checks outbound chunks in order, and
returns configured inbound chunks. It can also inject an open, write, or read
failure.

Contract tests cover:

- opening upload-pack and receive-pack sessions;
- preserving binary bytes and exchange order;
- explicit, idempotent close;
- close after a scripted failure;
- rejecting invalid options before a transport is opened;
- listing, resolving, creating, and compare-and-set updating refs;
- rejecting a stale ref update without changing the ref;
- storing and reading exact pack bytes in chunks;
- aborting incomplete pack writes without publication.

The fixture is test-only and is not exported as a production transport.

## Errors

The minimal boundary uses a typed `GitProtocolTransportException` with the
operation phase and retryability. It does not expose credentials or raw remote
responses. Protocol-specific errors remain owned by `git-parser`; future real
transports will extend transport diagnostics without changing client commands.

## Deferred Work

Three high-level follow-up tasks remain in the `docs/plans` task tree:

1. scripted upload-pack and receive-pack clients composed from this transport
   and existing wire primitives;
2. production repository backends implementing the independent ref and content
   ports;
3. the first real transport plus end-to-end remote fetch and push compatibility
   tests.

Smart HTTP authentication, SSH host-key policy, TCP sockets, protocol command
negotiation, pack parsing, and repository integration are deliberately outside
this slice.

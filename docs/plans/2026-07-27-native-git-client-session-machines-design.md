# Native Git Client Session Machines Design

## Goal

Add JGit-free native upload-pack and receive-pack clients whose protocol
conversations are driven by explicit state machines. Keep transport lifecycle,
service-specific Git behavior, and low-level wire framing in separate layers.

## Context

`core/git-parser` contains the prototype `GitMinimalWireMachine`, pkt-line
readers and writers, capability handling, side-band handling, and report-status
support. `core/git-protocol-client` establishes transport and session contracts
that exchange `ByteBuf` chunks without depending on a concrete network
protocol.

The client layer must not reproduce pkt-line parsing or implement the Git
conversation as an imperative sequence of transport reads and writes.
Upload-pack and receive-pack need different semantic phases, while transport
opening, I/O failure handling, and closing are common.

The word `scripted` describes the test transport fixture, not the production
clients.

## Architecture

The client path has four composable layers:

```text
GitUploadPackClient / GitReceivePackClient
                |
GitProtocolSessionMachine
                |
GitUploadPackClientMachine / GitReceivePackClientMachine
                |
GitWireMachine
                |
PhaseMachine<Event, Phase>
```

The public clients are thin synchronous facades. They construct a
service-specific client machine for one operation and ask the session machine
to drive it to a terminal result.

`GitProtocolSessionMachine` owns the transport lifecycle. It opens the
appropriate `GitProtocolSession`, executes actions requested by the client
machine, passes inbound chunks to that machine, and closes the session exactly
once on success or failure.

`GitUploadPackClientMachine` and `GitReceivePackClientMachine` own the semantic
Git protocol phases. They select supported capabilities, decide which request
must be written next, route parsed responses, and produce typed operation
results.

`GitWireMachine` is the production evolution of `GitMinimalWireMachine`. It
owns durable inbound wire phases, pkt-line framing, structured payload routing,
and transitions to raw pack forwarding. The client machines compose it instead
of parsing packet boundaries themselves.

The declarative lifecycle `StateMachine` is not used for the per-chunk protocol
path. Its module also provides the smaller `PhaseMachine`, which is the shared
state holder used by the session, client, and wire machines.

## Shared Phase Machine Contract

`PhaseMachine<E, P>` owns one current phase. A phase accepts one event and
returns the next phase. It exposes whether it is terminal and releases any
currently owned resources when closed.

The shared abstraction enforces these rules:

- the initial phase, events, and returned phases are non-null;
- a terminal phase cannot receive another event;
- a closed machine cannot receive another event;
- close is idempotent and closes only the current phase;
- previous phases are not closed automatically, so a transition must transfer
  or release resources it owns;
- the abstraction provides no scheduler, transport, threading, Git, or
  `ByteBuf` policy.

Each Git machine composes `PhaseMachine` with its own sealed event and phase
families. Actions such as read, write, complete, and fail remain part of the
client-machine contract instead of the reusable phase abstraction.

## Machine Boundary

A service-specific client machine does not call a transport or session
directly. It exposes its next required effect as one of these actions:

- write an owned outbound `ByteBuf`;
- read another inbound chunk;
- complete with a typed result;
- fail with a typed client error.

After an inbound chunk is supplied, the client machine advances its current
phase and exposes the next action. It may pass the chunk through
`GitWireMachine`, which routes complete structured payloads or raw pack slices
to the current service phase.

The action boundary keeps the service machines reusable with blocking,
asynchronous, HTTP, SSH, TCP, and scripted transports. The first session
machine executes actions synchronously because the current
`GitProtocolSession` contract is synchronous.

## Upload-Pack Client

`GitUploadPackClient` exposes ref discovery and fetch operations.

The upload-pack client machine owns these high-level phases:

1. read and validate the service or protocol capability advertisement;
2. write an `ls-refs` or fetch request;
3. read structured response sections;
4. transition to side-band or raw pack forwarding when the response announces
   pack data;
5. complete only after the response terminator and pack target complete
   successfully.

The client uses the dedicated v1 advertisement and protocol v2 response parsers
owned by `git-parser`. It must not add temporary line parsing while those
components are being implemented by the active wire-core task.

## Receive-Pack Client

`GitReceivePackClient` exposes one-ref push initially.

The receive-pack client machine owns these high-level phases:

1. read and validate the receive-pack advertisement;
2. select only capabilities supported by both sides;
3. write the ref update command and request terminator;
4. stream the caller-provided pack chunks;
5. read side-band output when negotiated;
6. parse report-status;
7. complete with accepted or rejected per-ref status.

The machine does not build Git objects or packs and does not update local
repository refs. It consumes an already prepared pack source and returns the
remote result.

## Buffer Ownership

Every boundary has explicit `ByteBuf` ownership:

- wire writers create outbound buffers owned by the client machine;
- the session machine releases an outbound buffer after the session write
  completes or fails;
- the session returns caller-owned inbound buffers;
- the session machine passes each inbound buffer to the client machine and
  releases the original reference according to the wire-machine ownership
  contract;
- raw pack targets own retained slices delivered to them;
- push pack sources return caller-owned chunks that the session machine
  releases after writing;
- structured fragments remain bounded and are released when their owning
  machine closes.

No client operation buffers a complete pack in additional memory.

## Errors and Closing

Transport failures preserve their operation phase and retryability.
Wire failures preserve typed wire phase, packet index, and byte offset.
Client failures add only the semantic operation and capability context needed
by callers. They do not store credentials, authorization headers, repository
contents, raw protocol responses, or unsanitized remote URIs.

The session machine always attempts to close an opened session. When an
operation has already failed, a close failure is suppressed on the primary
failure. When close is the only failure, it becomes the operation failure.
Every nested client and wire machine is also closed so incomplete headers,
payloads, or protocol phases are validated and owned fragments are released.

## Testing

Service-specific machine tests run without a real transport. They feed
fragmented `ByteBuf` chunks, inspect emitted actions, and cover:

- exact upload-pack discovery and fetch requests;
- capability selection and missing required capabilities;
- fragmented structured responses;
- exact side-band pack reconstruction and separated progress;
- one-ref receive-pack command framing;
- raw push pack streaming without changing source reader indexes;
- successful report-status, unpack failure, and per-ref rejection;
- malformed, truncated, and unexpectedly terminated responses;
- buffer release on every terminal path.

`GitProtocolSessionMachine` contract tests use
`ScriptedGitProtocolTransport`. They cover exact service, URI, options, ordered
read/write exchange, successful close, failure close, close suppression, and
transport error propagation.

Thin facade tests verify that each public client creates the correct service
machine and returns its typed result. The existing module boundary test
continues to reject JGit production dependencies.

## Scope

This task adds the session-driving and client-specific machines plus the thin
native clients. It consumes wire parsers from `git-parser` and does not
duplicate their work. It does not add a real network transport, production
repository backend, pack builder, pack parser, clone, checkout, merge, or
working-tree behavior.

# Native Git Wire Protocol Core

## Goal

Add a JGit-free shared Git wire protocol core for Orion's native Git client and
server implementations.

This layer should own the protocol primitives that must be identical across
native remote fetch, native remote push, upload-pack serving, receive-pack
serving, and transport tests:

- pkt-line framing;
- service request parsing;
- protocol version negotiation;
- capability parsing, selection, and advertisement;
- protocol v0/v1 advertisement helpers;
- protocol v2 command section parsing and writing;
- side-band and side-band-64k encoding/decoding;
- report-status parsing/writing primitives;
- sanitized protocol error handling;
- scripted wire fixtures.

The result should be a small protocol core used by higher-level upload-pack and
receive-pack services, rather than each feature hand-writing its own packet and
capability handling.

## Current State

The native protocol client primitives plan is client-focused. It defines pkt-line
framing, capability parsing, side-band handling, upload-pack client,
receive-pack client, and concrete outbound transports.

The native upload-pack serving plan states that server upload-pack can share
pkt-line and side-band codecs, but needs its own request parser and response
state machine.

The native receive-pack serving plan defines server-side advertisement, command
parsing, capability negotiation, pack ingestion, and report-status responses, but
does not isolate shared wire-format parsing from receive-pack policy.

The native transport plans focus on accepting Git clients over sockets, NIO, and
SSH command wiring. They do not define protocol grammar or service-specific
packet state machines.

`GitInternalService` currently reads the initial command using JGit pkt-line
helpers and delegates upload/receive streams to `GitRepository`. Native mode
needs that initial command parsing and later service packet handling without
JGit.

Without a shared core, native remote fetch/push, upload-pack serving, and
receive-pack serving risk duplicating subtly different pkt-line, capability,
side-band, and protocol-version behavior.

## Non-Goals

Do not implement object storage, ref storage, pack parsing, pack building,
upload-pack negotiation, receive-pack policy, or repository authorization in this
plan.

Do not replace the higher-level native protocol client plan. This plan extracts
the shared wire core that the client plan and server plans should consume.

Do not implement smart HTTP, SSH, or raw TCP transports here beyond the
transport-neutral stream/session boundaries needed by protocol code.

Do not implement every Git protocol extension immediately. Add a capability
registry that lets unsupported extensions be omitted or rejected clearly.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production wire core code. Existing legacy service
entry points may keep temporary JGit compatibility until their transport
boundary is converted to feed `ByteBuf` chunks into the native core. Tests may
compare packet sequences with Git CLI or JGit fixtures.

## Module Boundary

Create a small shared package or module such as:

```text
git-wire-protocol
```

It should be usable by:

- native protocol client primitives;
- native upload-pack serving;
- native receive-pack serving;
- native transport baseline tests;
- scripted protocol fixtures.

It should not depend on:

- repository storage implementations;
- JGit;
- HTTP client implementations;
- SSH client/server implementations;
- ACL or authorization services.

This module should use Netty `ByteBuf` as the production byte boundary. Depend
on the smallest practical Netty buffer artifact, not `netty-all`, so Git wire
code can share the same buffer model as `core/communication` without pulling in
transport stacks.

Add a dependency boundary test proving production classes in this module do not
import `org.eclipse.jgit` and do not depend on JGit artifacts transitively.

## Responsibility Zones

Current prototype zones:

- `GitFixedControlFrameReader` is currently a bounded pkt-line header cursor. It
  consumes only the four-byte hex pkt-line header, handles Git special packets
  `0000`, `0001`, and `0002`, rejects invalid lengths, and leaves payload or raw
  tail bytes in the original input buffer. If the header is complete in the
  current input, it advances `readerIndex` without copying. If the header is
  fragmented, it returns a `ControlState.MoreDataNeeded` carrying a bounded
  `CachingByteBuf` fragment owned by the caller state. The reader itself is
  stateless: callers pass the previous `ControlState` into `accept`, and the
  reader returns the next `ControlState`. Completed output is
  `Success(type, length)` where `length` is the pkt-line wire length.
- `CachingByteBuf` is a generic fixed-size cache for fragmented structural
  reads. `BufferedCaching` copies bytes into one owned buffer; `CompositeCaching`
  retains input slices in a composite buffer. Each use case chooses the strategy
  based on whether small-copy simplicity or no-copy slice retention is better.
- `FixedByteBufForwarder` is a generic fixed-length forwarding cursor. It
  forwards retained slices from inbound buffers to a sink until the declared
  byte count reaches zero.
- `GitMinimalWireMachine` processes accepted inbound buffers and returns whether
  the caller should release the original input reference after `accept`. It owns
  all durable wire state through one current phase object. `ControlPhase` carries
  the current `ControlState`; `RawSinkPhase` carries the completed
  `ControlSuccess`, fixed raw forwarding cursor, and lazy raw target creation
  state. Readers and `RawSink` are stateless helpers called by the machine. The
  machine routes on the current phase and forwards exactly `length - 4` pkt-line
  payload bytes to the raw target before returning to header reading. It does
  not store borrowed input buffers and does not manually advance `readerIndex`
  for control bytes. The current prototype accepts Git's fixed 65,520 byte
  pkt-line maximum rather than a per-machine structural capacity parameter.
- `GitMinimalWireMachine` owns lazy raw target creation. A complete control frame
  with no raw tail must not create a raw target; the raw target is created only
  when raw bytes are actually observed. The raw target factory receives
  `ControlSuccess` metadata from the reader, not a control byte buffer.
- `RawSink` is a final stateless helper. It forwards retained raw slices to the
  caller-owned raw target and owns no durable state. The raw target owns the
  retained raw slices passed to it. The caller releases the original inbound
  buffer when the state machine returns a positive release decision.
- `GitInitialServiceRequestParser` is the first native service request parser in
  `git-parser`. It reads exactly one socket-style data pkt-line from a `ByteBuf`,
  scans the payload as byte ranges, validates supported `git-upload-pack` and
  `git-receive-pack` services, extracts repository path and NUL-separated
  parameters without materializing a raw command string, and leaves subsequent
  buffer bytes for upload-pack or receive-pack. The old
  `GitInternalService.parse(InputStream)` path temporarily keeps JGit
  `PacketLineIn` until the production transport/session boundary can feed
  `ByteBuf` chunks into the native wire core.

Planned production zones:

- Transport adapters convert sockets, SSH commands, HTTP bodies, streams, or test
  fixtures into inbound `ByteBuf` chunks and call the wire session. Transport
  adapters should not parse Git grammar beyond transport-specific request
  metadata.
- `GitWireSession` is the connection-level state machine. It owns inbound buffer
  lifecycle after `accept`, phase transitions, structural buffer use, lazy raw
  target creation, reader states, raw payload state, and routing between control
  readers, pkt-line readers, raw payload bridges, and service-specific handlers.
  All durable parser state must be inspectable through this session/machine
  state, not hidden inside individual readers.
- Control readers are stateless helpers. Header readers consume only their
  headers and return typed state transitions. Bounded byte readers consume
  exactly known remaining structure bytes, make copy/no-copy decisions for
  fragmented input, and return the next caller-owned state. They leave lifecycle,
  durable state storage, and routing decisions to `GitWireSession`.
- `GitStructuralBuffer` is session-owned bounded storage for fragmented control
  data. It should be the only default place where small control fragments are
  copied across inbound buffers.
- `GitPktLineReader` parses pkt-line length/header transitions from caller-owned
  state and bounded control payload state owned by `GitWireSession`. It must not
  materialize large raw payloads; when pkt-line framing carries raw data, it
  should expose consumed ranges or stream retained slices through the session's
  raw bridge.
- `GitRawPayloadBridge` owns transitions from structured parsing into raw byte
  forwarding, including pack bodies and side-band band-1 payloads. It should
  forward retained slices through the stateless `GitRawSink` helper without
  copying complete raw packets into heap arrays.
- `GitRawSink` is a final stateless raw forwarding helper. It owns no durable
  state and accepts caller-owned raw target state plus retained input slices.
- `GitRawTarget` owns raw byte ingestion into the next subsystem, such as pack
  indexing, disk writes, quarantine storage, or test capture. It must explicitly
  define whether it consumes retained slices synchronously or asynchronously.
- Wire writers own outbound `ByteBuf` creation and packet framing. Service
  layers provide semantic commands or response data, not raw packet formatting.
- Upload-pack and receive-pack service layers own negotiation, authorization
  decisions, ref policy, pack creation or ingestion, and report-status semantics.
  They should consume wire primitives and raw targets rather than depending on
  transport buffers directly.
- Repository storage, pack indexing, object validation, ACL, and authorization
  remain outside the wire core.

## Core Value Objects

Introduce wire-level models:

- `GitProtocolVersion`: v0, v1, v2;
- `GitServiceName`: upload-pack, receive-pack, upload-archive if ever needed;
- `GitPktLine`;
- `GitPacketKind`: data, flush, delimiter, response-end;
- `GitPacketSequence`;
- `GitCapability`;
- `GitCapabilitySet`;
- `GitCapabilitySelection`;
- `GitServiceAdvertisement`;
- `GitProtocolSessionOptions`;
- `GitWireError`;
- `GitWirePhase`;
- `GitSideBandMode`;
- `GitSideBandPacket`;
- `GitWireSession`;
- `GitStructuralBuffer`;
- `GitRawSink`;
- `GitRawTarget`;
- `GitWireOutput`;
- `GitProtocolLimits`.

Keep these models wire-focused. Service-specific request models such as
`GitUploadPackRequest` and `GitReceiveCommand` should live in their service
layers and consume parsed wire primitives.

## Pkt-Line Codec

Implement binary-safe pkt-line encode/decode once.

Supported packet kinds:

- data packet with four-byte hex length;
- flush packet `0000`;
- delimiter packet `0001`;
- response-end packet `0002`.

Decoder requirements:

- reject non-hex length bytes;
- reject length `0003`;
- reject data packet lengths below `0004`;
- reject packet lengths above configured maximum;
- reject truncated packet payloads;
- preserve binary payload exactly;
- expose byte offset or packet index in errors;
- distinguish clean flush from unexpected end-of-stream;
- parse bounded control payloads through lazy structural storage only when an
  input fragment must survive until later input;
- stream raw payloads through the stateless `GitRawSink` helper to
  `GitRawTarget` without copying the whole packet into a second byte array.

Encoder requirements:

- preserve binary payload exactly;
- never add text newlines unless caller asks for a text packet helper;
- enforce maximum payload size before writing;
- support streaming writes to avoid buffering large sequences.

Text helpers should be explicit about LF handling because Git packet grammars
vary by context.

## ByteBuf Session Boundary

Use `ByteBuf` as the core parser boundary.

The core should expose a chunk-driven session API:

```text
boolean GitWireSession.accept(ByteBuf input, GitRawTarget rawTarget, GitWireOutput output)
```

The transport/session boundary coordinates accepted input buffer lifecycle. The
wire session consumes readable bytes, copies bounded fragmented control state
when needed, hands retained raw slices to downstream raw targets, and returns whether
the caller should release the original input reference after `accept`. Lower-level
readers must not release inbound buffers or hide durable protocol state. They
advance `readerIndex` over bytes they consume and return a narrow read state for
the current `accept` call. The wire session owns and stores that state between
calls, including incomplete fragmented-control state, so the whole parser mode is
visible in one session/machine object. Once complete, the session derives control
metadata from the caller-owned state for fragmented frames or from the current
input for no-copy frames. Calling a reader with a completed control state is an
invalid caller transition and must not consume the new input. Stream, channel,
socket, SSH, and HTTP adapters may exist outside the core, but they should adapt
into `ByteBuf` chunks before invoking wire parsing.

Rationale:

- `core/communication` already uses `ByteBuf` for ownership, slicing, and
  network handoff;
- native Git transports can feed pooled direct buffers without converting to
  `ByteBuffer`;
- parsing code can use `ByteBuf` absolute reads for fixed-width Git headers;
- output can be produced as `ByteBuf` chunks and flushed by the transport layer;
- compatibility adapters for `InputStream` and `OutputStream` stay at the edge
  while the protocol state machine remains buffer-native.

## Structural Buffer and Raw Bypass

Git wire sessions should not allocate structural storage on creation. Structural
storage is allocated only when a protocol/control structure is fragmented and
some consumed bytes must survive until a later input. The maximum pkt-line frame
length is the Git protocol limit of 65,520 bytes; services may impose stricter
limits for specific sections. Structural storage is only for:

- initial service request;
- pkt-line headers and bounded text payloads;
- capability lines;
- protocol v2 arguments and section delimiters;
- receive-pack command lines;
- report-status lines;
- bounded progress and error previews.

Raw pack data, blob data, and large side-band band-1 payloads must not be
retained in the structural buffer. Once the parser reaches a raw phase, readable
input slices are forwarded through `GitRawSink` to `GitRawTarget` and released
according to the target contract.

The accumulator should prefer a simple merged `ByteBuf` for structural data.
If a complete control structure is available in the current inbound `ByteBuf`,
the parser can read it directly from that buffer without copying. If the control
structure is incomplete and the parser must wait for later input, the bounded
byte reader copies only the consumed control fragment into lazy structural
storage. Release a fragmented inbound buffer only after the reader has consumed
or copied all bytes it needs from that input. If completing the control
structure leaves unread bytes in the same inbound buffer, the session continues
through later phases in the same `accept` call while the input remains readable.
If a no-copy control frame completes without raw tail, the session may keep
caller-owned state with a retained control slice until the control bytes have
been handed to the next target or until session close, while the caller can
release the original input reference according to the session's release decision.
Do not retain a large pooled inbound buffer only to preserve a small incomplete
control tail. The current prototype already supports split four-byte pkt-line
headers by copying the consumed header fragment into bounded structural storage
and continuing from the same input when the header completes with readable tail
bytes.

This gives the common path zero copy for complete structures while keeping memory
bounded for slow or fragmented clients. It should reject control payloads that
exceed the Git pkt-line limit or a stricter service limit rather than expanding
without bound.

The connection-level parser should be a small state machine around the specific
control and raw branches. It should create downstream raw targets lazily, only
when the state first observes bytes that actually need that target. For example,
reading a complete control frame with no raw tail should not create a raw target;
the target is created when the next inbound buffer contains raw bytes or when the
current buffer has a preserved readable tail after control parsing.
Control slices passed to target factories are call-scoped borrows; after factory
creation returns, the session may release the retained inbound buffer or
fragmented structural storage.

`CompositeByteBuf` can be used internally only when it measurably improves an
edge case. It should not be the default control-path representation because
fixed-width parsing, component cleanup, and reference ownership are simpler with
a merged structural buffer.

The session must distinguish three payload ownership modes:

- borrowed slice: used synchronously during the current parse step and not
  retained;
- retained slice: passed to a downstream component that explicitly owns release;
- copied structural bytes: small bounded control data kept in the reusable
  session buffer.

Diagnostics must be bounded. Trace formatting can keep packet metadata and a
small configurable preview, but the default transport path must not materialize
complete pkt-line payloads or raw pack tails just to log or round-trip them.

## Compatibility Entry Points

Keep compatibility adapters outside the core parser:

- `InputStream` to `ByteBuf` reader for the current `GitRepository` boundary;
- `OutputStream` writer for compatibility with JGit-backed repositories;
- socket, SSH, and HTTP adapters that already receive buffers or can allocate
  buffers at the transport edge;
- scripted fixture adapter that can replay directional byte chunks.

The first implementation may keep the existing `GitTransportInputStream` class as
an adapter for `GitRepository.upload(...)` and `GitRepository.receive(...)`, but
the parsing state machine should live below it and should not depend on
`InputStream`.

## Packet Readers and Writers

Add small stateless readers and stateful session/writer components:

- `GitWireSession`;
- `GitStructuralBuffer`;
- `GitPktLineReader`;
- `GitPktLineWriter`;
- `GitPacketSequenceReader`;
- `GitPacketSequenceWriter`;
- `GitRawPayloadBridge`;
- `GitRawSink`;
- `GitRawTarget`;
- `GitWireOutput`.

The raw payload bridge is needed for phases where the stream changes from
pkt-line framing to raw pack bytes or side-band packet streams.

Readers should expose:

- a method that accepts caller-owned state plus input;
- the next caller-owned read state;
- typed completion metadata;
- consumed bytes through the advanced `readerIndex`.

Readers must not own packet count, current phase, or fragmented-control state.
Those values belong to `GitWireSession`, so diagnostics and debugging can inspect
one composed state rather than chasing hidden reader fields.

Writers should expose:

- packet count;
- bytes written;
- flush/delimiter/response-end helpers;
- side-band wrappers.

## Initial Service Request

Native server transports need JGit-free parsing for the first command from a Git
client.

For local socket/SSH-style requests, parse:

```text
git-upload-pack '/repo.git'\0host=example\0\0
git-receive-pack '/repo.git'\0host=example\0\0
```

The parser should extract:

- service name;
- repository path;
- optional host parameter;
- extra parameters;
- requested protocol version when present;
- raw command for diagnostics after sanitization.

Validation:

- only supported service names are accepted;
- repository path is not empty;
- NUL-separated fields are well formed;
- path normalization remains outside this module but receives raw path and host;
- oversized initial request fails before repository lookup.

The native parser itself accepts `ByteBuf` only. Do not add `InputStream`,
`byte[]`, or packet-wrapper compatibility overloads to the parser; adapters
belong at the transport/session edge. The existing
`GitInternalService.parse(InputStream)` production path may temporarily keep JGit
`PacketLineIn` before authorization and repository opening until that edge is
converted to `ByteBuf`.

## Protocol Version Negotiation

Implement shared protocol-version handling.

Inputs:

- transport-provided `GIT_PROTOCOL=version=2` equivalent where available;
- initial request extra parameters;
- HTTP service discovery headers where the HTTP adapter supplies them;
- server-supported protocol versions;
- client-supported protocol versions for outbound use.

Policy:

- protocol v2 is preferred when both sides support it;
- upload-pack server may support v2 first with explicit v0/v1 compatibility
  decision;
- receive-pack server should support classic v0/v1-style push early because many
  clients still use it;
- unsupported requested versions produce typed protocol errors.

The wire core should decide version selection and expose it. Service layers
decide which commands are implemented under the selected version.

## Capability Registry

Add a capability registry with known capability descriptors:

- `agent`;
- `object-format`;
- `side-band`;
- `side-band-64k`;
- `multi_ack`;
- `multi_ack_detailed`;
- `thin-pack`;
- `ofs-delta`;
- `shallow`;
- `filter`;
- `ref-in-want`;
- `symref`;
- `peel`;
- `unborn`;
- `ls-refs`;
- `fetch`;
- `server-option`;
- `report-status`;
- `report-status-v2`;
- `delete-refs`;
- `atomic`;
- `push-options`;
- `quiet`.

The registry should preserve unknown capabilities as raw values while allowing
service layers to select only supported capabilities.

Capability parsing should support:

- bare capabilities;
- key-value capabilities;
- repeated values where Git permits them;
- NUL-separated first-ref v0/v1 capabilities;
- protocol v2 capability advertisements.

Unsupported client-requested capabilities should become typed failures when the
service requires rejection, or ignored/omitted where Git protocol permits.

## Advertisement Writers

Provide helpers for common advertisement packet shapes.

Protocol v0/v1 ref advertisement:

- first ref line includes NUL-separated capabilities;
- additional refs are plain lines;
- peeled tag lines use `^{}`
- flush terminates advertisement;
- empty repository behavior is explicit.

Protocol v2 advertisement:

- version line where required by transport;
- capability lines;
- delimiter/flush according to command grammar;
- `ls-refs` response helpers for refs, symrefs, peeled tags, and unborn refs.

The wire core should not decide which refs are visible. It should receive already
filtered ref advertisement rows from upload-pack or receive-pack service code.

## Protocol V2 Section Parser

Add a reusable parser for protocol v2 command requests.

It should parse:

- command name;
- command arguments;
- capability arguments;
- delimiter between command arguments and command body;
- packet body lines;
- flush;
- response-end where applicable.

It should not interpret upload-pack `want` or receive-pack commands directly.
Instead it returns structured command sections that service layers convert into
domain request objects.

Validation:

- missing command fails;
- unknown command is returned as unsupported for service mapping;
- duplicate sections fail when not allowed;
- maximum arguments and body packet counts are enforced;
- malformed line encodings include packet index and phase.

## Protocol V0/V1 Helpers

Add compatibility helpers for classic protocol flows:

- upload-pack advertisement with first-line capabilities;
- upload-pack wants/haves/done packet classification;
- ACK/NAK line writer/parser primitives;
- receive-pack advertisement with first-line capabilities;
- receive-pack command-line packet classification;
- transition from command packets to pack stream after flush.

These helpers should stay grammar-level. Upload-pack negotiation decisions and
receive-pack command policy remain in the service plans.

## Side-Band Core

Implement side-band encode and decode once.

Modes:

- no side-band: raw pack bytes or raw report-status packets;
- side-band;
- side-band-64k.

Bands:

- `1`: data;
- `2`: progress;
- `3`: fatal error.

Decoder requirements:

- reconstruct exact band-1 bytes by streaming them through `GitRawSink` to
  `GitRawTarget`;
- capture band-2 progress separately;
- turn band-3 payload into a typed fatal error;
- reject unknown band ids;
- enforce packet size and total payload limits;
- tolerate arbitrary packet boundaries.

Side-band packets use pkt-line framing, but band-1 payload is raw Git data after
the one-byte band id has been parsed. The decoder should read the pkt-line
length and band id as control data, then forward the remaining band-1 payload to
`GitRawTarget` through `GitRawSink` in available `ByteBuf` slices. It must not
accumulate the whole side-band-64k payload in the structural buffer. Band-2
progress and band-3 fatal messages are control data and must remain bounded by
protocol limits.

Encoder requirements:

- split payload into legal band packet sizes;
- send progress only when negotiated;
- send fatal errors through band 3 after side-band mode starts;
- preserve exact data bytes in band 1.

Both client and server code should use the same implementation.

## Report-Status Core

Receive-pack client and server both need report-status parsing/writing.

Add primitives for:

- `unpack ok`;
- `unpack <error>`;
- `ok <ref>`;
- `ng <ref> <reason>`;
- flush termination;
- optional report-status-v2 extensions later.

The wire core should parse/write report-status lines. Service code decides
whether a ref update succeeded and sanitizes domain-specific reasons before
writing them.

## Error Model

Use typed wire errors:

- unsupported service;
- unsupported protocol version;
- unsupported capability;
- malformed initial request;
- malformed pkt-line length;
- truncated packet;
- packet too large;
- sequence limit exceeded;
- unexpected packet kind;
- unexpected end of stream;
- invalid capability advertisement;
- invalid protocol v2 section;
- invalid side-band id;
- side-band fatal error;
- report-status parse failure;
- timeout or cancellation.

Errors should include:

- phase;
- packet index or byte offset where known;
- service name when known;
- selected protocol version when known;
- retryability where meaningful.

Errors must not include credentials, private key paths, object contents, raw pack
payloads, or hidden ref names in user-facing messages.

## Limits

Add protocol limits:

- fixed Git pkt-line frame maximum, 65,520 bytes;
- maximum pkt-line payload size;
- maximum packet count per section;
- maximum capability count;
- maximum capability value length;
- maximum initial command size;
- maximum protocol v2 argument count;
- maximum side-band progress bytes retained;
- maximum report-status lines.

Do not add a default maximum "raw pack bytes retained in memory" knob. Raw pack
payloads should bypass the structural buffer and be bridged through `GitRawSink`
to `GitRawTarget` as `ByteBuf` slices. Any caller that deliberately records raw
pack bytes for a fixture or debug dump must opt in with an explicit byte limit.

Service layers can add stricter limits for upload-pack wants/haves or
receive-pack command counts.

## Integration With Native Client Primitives

The native protocol client primitives should depend on this core for:

- pkt-line encoding/decoding;
- capability parsing;
- protocol v2 command construction/parsing;
- side-band demultiplexing;
- report-status parsing;
- error and limit models.

Client-specific code should keep:

- outbound transport adapters;
- upload-pack client behavior;
- receive-pack client behavior;
- remote authentication and TLS/SSH policy mapping.

This lets native remote single-file fetch and push share exact wire behavior with
server-side tests.

## Integration With Upload-Pack Serving

Native upload-pack serving should depend on this core for:

- service request parsing where needed;
- protocol version selection;
- ref advertisement writing;
- protocol v2 command section parsing;
- v0/v1 want/have/done packet classification;
- ACK/NAK line helpers;
- side-band response encoding;
- protocol error packets.

Upload-pack service code should keep:

- ref visibility;
- fetch access checks;
- want/have negotiation;
- object enumeration;
- pack building;
- upload statistics.

## Integration With Receive-Pack Serving

Native receive-pack serving should depend on this core for:

- service request parsing where needed;
- classic receive-pack advertisement writing;
- receive command packet classification;
- capability selection;
- command-to-pack stream transition;
- side-band response encoding;
- report-status writing;
- protocol-safe errors.

Receive-pack service code should keep:

- write/create authorization;
- command policy;
- pack ingestion;
- object validation;
- ref updates;
- receive events.

## Integration With Transports

Transport layers should adapt byte streams to `ByteBuf` chunks for the wire
core:

- classic socket transport;
- future NIO transport;
- SSH command transport;
- smart HTTP server adapter if added later;
- outbound client transports from the client primitives plan.

The wire core should not know whether bytes came from TCP, HTTP, SSH, or an
in-memory fixture. It should operate on `GitWireSession` inputs and outputs and
expose structured wire state. Stream and channel adapters are compatibility
layers outside the parser state machine.

## Scripted Fixtures

Build shared fixture tools:

- pkt-line fixture builder;
- capability advertisement builder;
- protocol v2 request/response builder;
- side-band fixture builder;
- report-status fixture builder;
- malformed sequence builder;
- golden packet sequence serializer;
- stream transcript recorder with secret redaction.

Fixtures should be usable by:

- native protocol client tests;
- upload-pack serving tests;
- receive-pack serving tests;
- transport baseline tests.

Network access should not be required for unit tests.

## Compatibility Strategy

Use Git CLI and JGit only as test or fixture references.

Compatibility checks:

- packet sequences accepted by Git CLI clients;
- packet sequences produced by common Git servers;
- v0/v1 first-line capabilities;
- protocol v2 section delimiters;
- side-band packet size behavior;
- receive-pack report-status behavior.

Where Git behavior differs by version, record fixture source and expected
behavior. Production code should implement a stable subset deliberately instead
of accepting every observed malformed variant.

## Implementation Phases

Phase 1: Module and dependency boundary.

Create shared wire protocol package/module and dependency test that production
code has no JGit dependency. Add the minimal Netty buffer dependency needed for
`ByteBuf` without depending on `netty-all`.

Phase 2: ByteBuf session and structural buffer.

Implement `GitWireSession`, `GitStructuralBuffer`, `GitRawSink`, `GitRawTarget`,
and `GitWireOutput`. Add tests for input ownership, release behavior, fixed Git
pkt-line maximum length, no structural allocation when a control structure is
complete in the inbound buffer, session-owned fragmented-control copying into
lazy structural storage, output methods throwing before readiness, visible
composed session state, bounded preview diagnostics, and raw bypass handoff.

Phase 3: Pkt-line codec.

Implement binary-safe encoder/decoder, packet readers/writers, packet kinds,
limits, and malformed input errors. Start with an incremental `ByteBuf` reader
that can parse headers and bounded control payloads split across input chunks
without materializing raw payloads.

Phase 4: Initial service request parser.

Implement `ByteBuf`-only parsing for `git-upload-pack` and `git-receive-pack`
initial commands in the native core. Keep legacy `InputStream` service parsing
on JGit until the native transport/session boundary can supply `ByteBuf` input.

Phase 5: Capability registry.

Parse and write capability sets for v0/v1 first-ref lines and protocol v2
advertisements. Preserve unknown capabilities.

Phase 6: Protocol version negotiation.

Implement selected-version logic from transport hints and server/client support
policy.

Phase 7: Advertisement helpers.

Write v0/v1 ref advertisements and protocol v2 capability/ls-refs response
packet sequences from caller-provided ref rows.

Phase 8: Protocol v2 section parser.

Parse command sections, arguments, delimiters, body packets, flush, and
response-end with limits.

Phase 9: Side-band core.

Implement side-band and side-band-64k encoder/decoder. Stream exact band-1 bytes
through `GitRawSink` to `GitRawTarget`; keep progress and fatal messages bounded.

Phase 10: Report-status core.

Parse and write receive-pack report-status packet sequences.

Phase 11: Compatibility adapters.

Add `InputStream`/`OutputStream` compatibility adapters for the existing
`GitRepository` boundary and scripted fixtures. Keep adapters outside the core
parser API.

Phase 12: Service integration.

Refactor native protocol client primitives, native upload-pack serving, and
native receive-pack serving to use the shared core instead of feature-local
packet logic.

Phase 13: Transport fixtures.

Wire scripted transcripts into native transport baseline tests and add Git CLI
compatibility fixtures where practical.

## Verification

Cover at least these cases:

- production wire protocol module has no JGit dependency;
- production wire protocol module depends on Netty buffer APIs without pulling in
  `netty-all`;
- session parser accepts `ByteBuf` chunks and releases consumed input buffers,
  except when a no-copy complete control frame must be held until raw target
  creation or session close;
- complete control structures available in one inbound `ByteBuf` are parsed
  directly without copying into the structural buffer, and the state machine
  continues using the same input range based on the advanced `readerIndex`;
- fragmented control structures are copied into lazy structural storage and the
  original inbound buffers are released only when fully consumed;
- fragmented control completion preserves the completing inbound buffer when it
  still contains readable bytes for the next phase;
- structural storage keeps bounded control data within the fixed Git pkt-line
  limit and is not allocated for complete no-copy frames;
- raw pack payload bypasses the structural buffer and reaches `GitRawTarget`
  through `GitRawSink`;
- pkt-line codec handles data, flush, delimiter, response-end, binary payloads,
  malformed hex, short lengths, oversized packets, and truncation;
- encoder preserves payload bytes and only adds LF through explicit text helper;
- packet reader reports packet index and phase on malformed input;
- initial service request parser accepts upload-pack and receive-pack commands;
- initial service request parser rejects unknown service, empty path, malformed
  NUL fields, and oversized command;
- protocol version negotiation prefers v2 when supported and rejects unsupported
  requested versions;
- capability parser handles bare, key-value, repeated, unknown, v0/v1, and v2
  capabilities;
- v0/v1 advertisement writer puts capabilities only on the first ref line;
- empty repository advertisement behavior is deterministic;
- protocol v2 section parser handles command, arguments, delimiter, body, flush,
  and response-end;
- protocol v2 parser rejects duplicate or malformed sections according to
  service policy;
- side-band decoder streams exact band-1 pack bytes split across packets and
  input buffers;
- side-band progress retention is bounded and does not retain pack data;
- side-band encoder splits payload into legal packet sizes;
- side-band fatal payload becomes a typed error;
- report-status parser handles unpack ok, unpack failure, per-ref ok, per-ref
  reject, and malformed lines;
- upload-pack serving tests can use shared advertisement, v2 parser, and
  side-band helpers;
- receive-pack serving tests can use shared advertisement, command transition,
  side-band, and report-status helpers;
- native client primitive tests use the same pkt-line/capability/side-band
  implementation as server tests;
- transport baseline scripted transcripts can be replayed without network
  access;
- user-visible protocol errors are sanitized.

## Resolved Decisions

The first server integration should replace JGit pkt-line use in
`GitInternalService` only after a native transport/session boundary can feed
`ByteBuf` input into the wire core.

## Open Questions

Should the existing client-focused protocol plan be renamed or refactored after
this shared core exists, or should it remain as a client feature plan that
depends on the core?

How much protocol v0/v1 upload-pack support should live in shared helpers versus
the upload-pack service state machine?

Should smart HTTP server-side Git support be part of this core later, or a
separate transport adapter plan?

Should transcript fixtures store raw packet bytes, decoded packet models, or both
for easier debugging?

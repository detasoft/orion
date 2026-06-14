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

Do not depend on JGit in production code. Tests may compare packet sequences with
Git CLI or JGit fixtures.

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

Add a dependency boundary test proving production classes in this module do not
import `org.eclipse.jgit` and do not depend on JGit artifacts transitively.

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
- stream packet payloads through the caller's buffer or channel without copying
  the whole packet into a second byte array.

Encoder requirements:

- preserve binary payload exactly;
- never add text newlines unless caller asks for a text packet helper;
- enforce maximum payload size before writing;
- support streaming writes to avoid buffering large sequences.

Text helpers should be explicit about LF handling because Git packet grammars
vary by context.

## Streaming Entry Point

Use `ReadableByteChannel` as the core parser boundary and keep `InputStream` as a
compatibility adapter.

Rationale:

- native socket/NIO work can feed channels directly;
- `InputStream` callers can be supported through `Channels.newChannel(input)`;
- a channel reader can own a small fixed header buffer and stream payload bytes
  into caller-provided `ByteBuffer` instances;
- the implementation can avoid allocating a full packet copy only for parser
  diagnostics.

The first implementation may keep the existing `GitTransportInputStream` class as
an adapter for `GitRepository.upload(...)` and `GitRepository.receive(...)`, but
the parsing state machine should live below it and should not depend on
`InputStream`.

Diagnostics must be bounded. Trace formatting can keep packet metadata and a
small configurable preview, but the default transport path must not materialize
complete pkt-line payloads or raw pack tails just to log or round-trip them.

## Packet Readers and Writers

Add small stateful readers/writers:

- `GitPktLineChannelReader`;
- `GitPktLineReader`;
- `GitPktLineWriter`;
- `GitPacketSequenceReader`;
- `GitPacketSequenceWriter`;
- `GitRawPayloadBridge`.

The raw payload bridge is needed for phases where the stream changes from
pkt-line framing to raw pack bytes or side-band packet streams.

Readers should expose:

- packet count;
- bytes read;
- current phase;
- limit state;
- cancellation/timeout hook.

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

`GitInternalService` can then use this parser before authorization and repository
opening.

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

- reconstruct exact band-1 bytes;
- capture band-2 progress separately;
- turn band-3 payload into a typed fatal error;
- reject unknown band ids;
- enforce packet size and total payload limits;
- tolerate arbitrary packet boundaries.

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

- maximum pkt-line payload size;
- maximum packet count per section;
- maximum capability count;
- maximum capability value length;
- maximum initial command size;
- maximum protocol v2 argument count;
- maximum side-band progress bytes retained;
- maximum report-status lines.

Do not add a default maximum "raw pack bytes retained in memory" knob. Raw pack
payloads should be bridged as streams or channels. Any caller that deliberately
records raw pack bytes for a fixture or debug dump must opt in with an explicit
byte limit.

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

Transport layers should adapt byte streams to the wire core:

- classic socket transport;
- future NIO transport;
- SSH command transport;
- smart HTTP server adapter if added later;
- outbound client transports from the client primitives plan.

The wire core should not know whether bytes came from TCP, HTTP, SSH, or an
in-memory fixture. It should operate on streams/sessions and expose structured
wire state.

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
code has no JGit dependency.

Phase 2: Pkt-line codec.

Implement binary-safe encoder/decoder, packet readers/writers, packet kinds,
limits, and malformed input errors. Start with a channel-backed streaming reader
and an `InputStream` adapter test that proves normal reads do not allocate a
second full-packet buffer.

Phase 3: Initial service request parser.

Replace JGit helper use for parsing `git-upload-pack` and `git-receive-pack`
initial commands in a native-compatible path.

Phase 4: Capability registry.

Parse and write capability sets for v0/v1 first-ref lines and protocol v2
advertisements. Preserve unknown capabilities.

Phase 5: Protocol version negotiation.

Implement selected-version logic from transport hints and server/client support
policy.

Phase 6: Advertisement helpers.

Write v0/v1 ref advertisements and protocol v2 capability/ls-refs response
packet sequences from caller-provided ref rows.

Phase 7: Protocol v2 section parser.

Parse command sections, arguments, delimiters, body packets, flush, and
response-end with limits.

Phase 8: Side-band core.

Implement side-band and side-band-64k encoder/decoder and exact band-1 stream
reconstruction.

Phase 9: Report-status core.

Parse and write receive-pack report-status packet sequences.

Phase 10: Service integration.

Refactor native protocol client primitives, native upload-pack serving, and
native receive-pack serving to use the shared core instead of feature-local
packet logic.

Phase 11: Transport fixtures.

Wire scripted transcripts into native transport baseline tests and add Git CLI
compatibility fixtures where practical.

## Verification

Cover at least these cases:

- production wire protocol module has no JGit dependency;
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
- side-band decoder reconstructs exact band-1 pack bytes split across packets;
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

## Open Questions

Should the existing client-focused protocol plan be renamed or refactored after
this shared core exists, or should it remain as a client feature plan that
depends on the core?

Should the first server integration replace JGit pkt-line use in
`GitInternalService` immediately, or only inside native backend feature flags?

How much protocol v0/v1 upload-pack support should live in shared helpers versus
the upload-pack service state machine?

Should smart HTTP server-side Git support be part of this core later, or a
separate transport adapter plan?

Should transcript fixtures store raw packet bytes, decoded packet models, or both
for easier debugging?

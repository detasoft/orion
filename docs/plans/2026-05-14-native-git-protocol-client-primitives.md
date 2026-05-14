# Native Git Protocol Client Primitives

## Goal

Add backend-independent Git protocol client primitives that Orion can use for
JGit-free remote fetch and push workflows.

This plan extracts the shared protocol layer needed by native remote single-file
fetch and native remote single-file push: pkt-line framing, capability parsing,
protocol v2 commands, side-band handling, upload-pack fetch, receive-pack push,
and transport adapters.

## Current State

Orion currently has server-side Git command handling through `GitInternalService`.
That path reads the initial command line with JGit pkt-line helpers, authorizes
the repository, and delegates upload-pack and receive-pack work to the concrete
`GitRepository`.

`JGitRepository` remains the concrete protocol implementation for normal serving.
It delegates upload and receive streams to JGit `UploadPack` and `ReceivePack`.

The native Git transport plans cover Orion accepting local Git clients over
classic sockets, future NIO, and SSH command wiring. They do not define a
client-side implementation for talking to remote Git servers.

The remote single-file fetch and push plans both need the same low-level client
protocol layer. Without a shared plan, pkt-line, capability parsing, side-band,
smart HTTP, SSH, upload-pack, and receive-pack behavior would be duplicated in
feature-specific code.

## Non-Goals

Do not replace Orion's server-side `GitInternalService` in this phase.

Do not replace JGit-backed repository serving in this phase.

Do not parse pack contents here. The protocol client should extract pack streams
and hand them to the Orion pack parser and pack builder layers.

Do not implement clone, checkout, merge, rebase, or working-tree behavior.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code. Tests may compare against Git CLI
behavior and recorded protocol fixtures.

Do not implement every historical Git protocol variant first. Start with
protocol v2 where possible, plus explicit compatibility points for v0/v1.

## Module Boundary

Create a small module or package that can be used by remote Git features without
pulling in JGit:

```text
git-protocol-client
```

The module should expose:

- pkt-line codec;
- capability and response parsers;
- protocol v2 command builders;
- side-band demultiplexer;
- upload-pack client;
- receive-pack client;
- transport abstraction;
- scripted transport test utilities.

Add a dependency boundary test that fails if production classes in this module
import `org.eclipse.jgit` or depend on the JGit Maven artifact.

## Pkt-Line Codec

Implement pkt-line encoding and decoding as a standalone primitive.

Supported packet kinds:

- data packet;
- flush packet `0000`;
- delimiter packet `0001`;
- response-end packet `0002`.

The decoder must reject:

- non-hex length;
- length below the minimum packet size;
- declared length larger than configured maximum;
- truncated payload;
- unexpected binary data in text-only contexts;
- line endings that are invalid for the current parser.

The encoder should support binary payloads and text helpers. Text helpers should
not silently add or remove line endings unless the caller requests it.

## Capability Model

Add capability value objects that preserve both normalized names and raw values:

- service name;
- protocol version;
- object-format;
- agent;
- side-band and side-band-64k;
- multi_ack variants;
- thin-pack;
- ofs-delta;
- shallow;
- filter;
- ref-in-want;
- report-status and report-status-v2;
- delete-refs;
- atomic;
- push-options.

Unknown capabilities should be retained and exposed to callers. The client should
only select capabilities it understands.

## Protocol V2 Core

Implement protocol v2 request and response support:

- initial capability advertisement;
- command section;
- capability arguments;
- delimiter handling between sections;
- flush and response-end handling;
- server error parsing.

Start with these commands:

- `ls-refs`;
- `fetch`;
- receive-pack command negotiation where the selected transport exposes v2 or
  service-specific capability advertisement.

The protocol layer should keep command construction explicit so feature clients
can see exactly which wants, haves, filters, depths, and ref prefixes are sent.

## Upload-Pack Client

Add a reusable upload-pack client for remote fetch features.

First supported operations:

- discover refs for a requested branch, tag, full ref, or prefix;
- request peeled tag data when available;
- fetch explicit object ids;
- fetch a ref tip with optional depth;
- request `filter blob:none` when advertised;
- request side-band-64k when advertised;
- capture one returned pack stream;
- expose progress and server errors separately from pack bytes.

The upload-pack client should not decide application behavior such as "load this
path". It should return refs, capabilities, and pack stream results to higher
layers.

## Receive-Pack Client

Add a reusable receive-pack client for remote push features.

First supported operations:

- discover remote refs and receive-pack capabilities;
- send one ref update command with old id, new id, and ref name;
- select `report-status`;
- select `side-band-64k` when available;
- optionally select `atomic` when a future multi-ref request needs it;
- stream a caller-provided pack payload;
- parse unpack status and per-ref status;
- expose progress and server errors separately from command results.

The receive-pack client should not build Git objects or packs. It should accept
already prepared ref commands and pack streams from higher layers.

## Side-Band Handling

Implement side-band and side-band-64k demultiplexing:

- band 1: pack data;
- band 2: progress messages;
- band 3: fatal errors.

Progress messages should be captured for diagnostics but must not be logged at a
level that leaks repository names, credentials, or file content by default.

Fatal errors should become typed protocol failures with the raw message preserved
only in sanitized form.

Band handling must tolerate packet boundaries that split pack data arbitrarily.
The pack stream passed to the pack parser must be exactly the concatenated band-1
payload.

## Transport Abstraction

Define a transport boundary that provides bidirectional packet streams:

```text
GitProtocolTransport.open(service, remoteUri, options) -> GitProtocolSession
```

Session responsibilities:

- write request packets and raw pack bytes;
- read response packets and raw pack bytes;
- expose remote address and selected service for diagnostics;
- close cleanly on success, failure, and timeout;
- report retryable versus non-retryable I/O failures.

Transport options:

- connect timeout;
- read timeout;
- write timeout;
- total operation timeout;
- maximum packet size;
- maximum pack bytes;
- authentication reference;
- TLS and host-key policy;
- proxy policy where applicable.

## Concrete Transports

Add transports incrementally.

Phase order:

1. scripted in-memory transport for unit tests;
2. `git://` TCP transport for controlled local test servers;
3. smart HTTP(S) transport for hosted Git services and token auth;
4. SSH transport for private-key auth and direct `git-upload-pack` /
   `git-receive-pack` execution.

The `git://` transport should be treated as useful for controlled tests and
trusted networks only. Production remote ACL use should prefer HTTPS or SSH.

Smart HTTP(S) must implement Git service discovery endpoints and POST request
framing without depending on JGit. Redirects, TLS validation, proxy configuration,
and authentication must be explicit.

SSH must validate known hosts by default. A permissive host-key mode should be
test-only or require explicit insecure configuration.

## Error Model

Use typed failures instead of generic I/O exceptions at feature boundaries:

- transport unavailable;
- authentication failed;
- authorization denied;
- TLS or host-key verification failed;
- protocol version unsupported;
- capability missing;
- malformed packet;
- server error packet;
- side-band fatal error;
- unexpected end of stream;
- pack size limit exceeded;
- remote ref rejected;
- remote unpack failed;
- timeout.

Failures should include sanitized diagnostics: service, remote host, protocol
phase, selected capabilities, and retryability. Never include credentials, HTTP
authorization headers, private key paths if those are secret references, or file
contents.

## Test Fixtures

Build fixture support before real transports:

- scripted protocol sessions for upload-pack and receive-pack;
- raw pkt-line fixture files for golden parsing tests;
- side-band fixture builder;
- malformed response fixtures;
- fake pack byte streams with configurable size;
- record/replay fixtures for a small local Git CLI server when needed.

Tests should avoid requiring network access. Real remote provider compatibility
tests can be integration tests gated behind explicit configuration.

## Phased Plan

Phase 1: Dependency boundary and package layout.

Create the module or package, wire it into the build, and add a boundary test
that production code has no JGit dependency.

Phase 2: Pkt-line codec.

Implement binary-safe pkt-line encoder and decoder with maximum packet size
limits and precise malformed-input errors.

Phase 3: Capability and protocol v2 parser.

Parse capability advertisements, protocol v2 command responses, flush,
delimiter, response-end, and server errors.

Phase 4: Side-band demultiplexer.

Implement side-band and side-band-64k parsing with exact band-1 pack byte
reconstruction and sanitized progress/error reporting.

Phase 5: Scripted upload-pack client.

Implement `ls-refs` and `fetch` over scripted transports. Return refs,
capabilities, and pack streams without parsing pack contents.

Phase 6: Scripted receive-pack client.

Implement single-ref update command writing, pack streaming, report-status
parsing, and remote reject mapping over scripted transports.

Phase 7: `git://` transport.

Implement TCP service request framing for controlled local upload-pack and
receive-pack tests.

Phase 8: Smart HTTP(S) transport.

Implement service discovery and POST framing for upload-pack and receive-pack,
including token/basic auth hooks and TLS policy.

Phase 9: SSH transport.

Implement SSH command execution for upload-pack and receive-pack with private-key
auth and known-hosts verification.

Phase 10: Feature integration.

Use the protocol client from native remote single-file fetch and push. Remove any
feature-local duplicate pkt-line, side-band, or capability parsing code.

## Open Questions

Should the protocol client live under `core/git-engine`, a new `core/git-protocol`
module, or a submodule near `net/git-transport`?

Should the first real transport be smart HTTP(S) because remote hosted Git
providers use it heavily, or `git://` because it gives the smallest controlled
end-to-end test?

Should protocol v0/v1 support be required before production use, or should Orion
initially require protocol v2 remotes for native single-file operations?

Which HTTP client should be used for smart HTTP(S) so timeout, proxy, TLS, and
test fixture behavior fit the rest of Orion?

How should SSH key and known-host secret references be shared with existing ACL
configuration without leaking filesystem paths in errors?

Should progress messages be stored on the result object, sent to logs at trace,
or discarded by default?

## Verification

Cover at least these cases:

- production protocol client module has no JGit dependency;
- pkt-line encoder and decoder handle data, flush, delimiter, response-end,
  binary payloads, malformed hex lengths, short lengths, oversized packets, and
  truncated payloads;
- capability parser preserves unknown capabilities and parses known values;
- protocol v2 parser handles command responses with delimiters and response-end;
- side-band demultiplexer reconstructs exact pack bytes from split band-1
  packets;
- side-band progress is separated from pack data;
- side-band fatal error becomes a typed protocol failure;
- upload-pack `ls-refs` returns matching refs and peeled tags from scripted
  fixtures;
- upload-pack `fetch` sends wants, depth, filter, and side-band capability only
  when selected and advertised;
- receive-pack command writer sends old id, new id, ref name, and capabilities in
  the expected packet sequence;
- receive-pack report-status parser maps unpack success, unpack failure,
  per-ref ok, and per-ref reject;
- packet and pack size limits abort before unbounded memory growth;
- authentication and transport failures are sanitized;
- `git://` local transport can complete scripted upload-pack and receive-pack
  smoke tests when enabled;
- smart HTTP(S) service discovery handles valid advertisement, auth failure, and
  malformed content type;
- SSH transport validates known hosts by default;
- native remote single-file fetch and push use this shared client instead of
  duplicating protocol parsing.

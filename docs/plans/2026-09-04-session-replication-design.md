# Agent Session Journal Replication Design

Status: approved on 2026-09-04.

## Context

The central agent session server already has the shared Agent protocol and a
durable filesystem journal. The next stage must connect those pieces with an
actual low-level Jetty HTTP/2 stream endpoint while leaving authentication,
connection takeover, and durable agent/session registries to the following
`control-and-registries` task.

The replication transport is disposable. A session journal and its durable
server cursor are identified by `SessionId`, never by an HTTP/2 connection or
stream ID. Reconnects and temporarily overlapping physical streams must
therefore converge through storage instead of a transient ownership map.

## Boundary

This stage implements:

- a transport-neutral replication core backed by `SessionJournalStorage`;
- incremental decoding of the mixed session stream wire format;
- a low-level Jetty HTTP/2 endpoint for
  `POST /agent/session/{sessionId}`;
- established agent-context lookup through an injected interface;
- explicit retained-history gap reporting through an injected recorder;
- per-stream backpressure, acknowledgement, failure isolation, and live peer
  tests.

The established context contains the authenticated `AgentId`, but this stage
does not authenticate credentials or decide whether the agent owns the
requested session. The next task will install contexts, fence superseded
connections, and enforce session ownership.

## Architecture

Replication has three layers.

The protocol layer owns a specialized mixed-stream decoder. Its first complete
CBOR item must decode as `SESSION_OPEN`; following items decode as
`SessionEventRecord`. The decoder is incremental and bounded, so the boundary
between the opening message and journal data may occur anywhere within or
between HTTP/2 DATA frames. Event records retain their exact encoded bytes,
unknown event types, opaque payloads, and optional future fields.

The transport-neutral core processes the opening message, derives the current
cursor from `SessionJournalStorage`, detects retained-history gaps, and appends
decoded event batches. It returns protocol responses and typed failures to the
endpoint. It has no Jetty types and keeps no independent replication cursor.

The Jetty layer handles request routing, established-context lookup, stream
lifecycle, DATA demand, response frames, resets, and borrowed-buffer release.
It uses Jetty's low-level HTTP/2 `Stream.Listener` API rather than servlet or
high-level HTTP request abstractions. A live peer test mounts the endpoint in a
real `HTTP2ServerConnectionFactory`.

## Established Agent Context

The endpoint resolves an `EstablishedAgentContext` for the low-level Jetty
session through an injected provider. Absence of a context rejects the request
as unauthenticated. The context exposes `AgentId` for attribution but no
session-ownership predicate.

The future control endpoint will establish and remove these contexts as
connections authenticate, reconnect, and become obsolete. Replication does
not infer identity from the request path, `SESSION_OPEN`, or physical stream.

## Opening and Resume

For a valid request with an established context, the endpoint returns an open
HTTP success response and begins demanding request data. The first decoded
item must be `SESSION_OPEN`, and its `SessionId` must equal the path ID.

The core reads the last durable event ID from storage and returns
`SESSION_SYNC(sessionId, cursor)`. An empty server journal produces a null
cursor. No acknowledgement state is stored outside the journal.

When a non-empty server cursor precedes the advertised
`firstAvailableEventId`, the unavailable interval is an explicit integrity
gap. The core records the gap before returning `SESSION_SYNC` with the actual
durable cursor. AgentD can then begin at its retained floor. The gap recorder
is an injected output port because durable session metadata belongs to
`control-and-registries`; repeated reports of the same gap must be idempotent.

## Append and Acknowledgement

After opening, every complete input item is a `SessionEventRecord`. Records
decoded together are submitted as one ordered storage batch. The storage
contract validates order, preserves exact bytes, ignores byte-identical
duplicates, and rejects conflicting bytes for one event ID.

Only a successful durable append may produce
`SESSION_SYNC(sessionId, durableThrough)`. This includes retries where another
stream already stored some or all identical records. A lost response therefore
causes harmless resend, and reconnect always resumes from storage rather than
from remembered transport state.

## Concurrent Physical Streams

The server permits multiple physical HTTP/2 streams to replicate the same
logical `SessionId`. Each stream has its own decoder, response ordering, and
flow-control state. `SessionJournalStorage` supplies their only coordination:
its session-local lock serializes append, overlapping identical records are
duplicates, and different histories produce a conflict.

This tolerance allows a replacement stream to make progress before an old
connection has observed its failure. It does not grant session ownership.
Once control registries exist, they will decide which established agent
connections may open a session stream and close obsolete connections.

## Backpressure and Fairness

The endpoint synchronously decodes each borrowed Jetty DATA buffer into owned
protocol values and releases the buffer before scheduling blocking work. At
most one storage/response operation is active for a physical stream. The next
`stream.demand()` occurs only when that operation has completed, except when a
bounded decoder needs more bytes to finish the current item.

Independent streams use virtual-thread execution. A slow append therefore
withholds demand only from its own stream, while other sessions and overlapping
streams continue. The filesystem storage already uses a separate append lock
per logical session.

## Failures and Stream Lifecycle

Invalid method, path, or path `SessionId` is rejected before body processing.
A missing established context returns an unauthenticated response. After the
success response has opened, protocol failures reset only that stream.

The following are protocol failures:

- a first item other than `SESSION_OPEN`;
- disagreement between path and message `SessionId`;
- malformed, incomplete, or oversized CBOR;
- invalid event ordering or conflicting duplicate bytes.

Storage I/O, stored corruption, gap-recorder failure, and response-write
failure are internal stream failures. Neither category closes healthy session
streams. Stream reset, failure, or clean end releases decoder and pending
state. Lifecycle callbacks are idempotent so late callbacks cannot send more
responses after terminal failure.

## Verification

Protocol tests cover split and coalesced opening/event items, bounded partial
items, unknown event types, optional tails, malformed input, and incomplete
end-of-stream input.

Core tests cover empty and existing cursors, explicit retained-history gaps,
append acknowledgement timing, identical retries, conflicts, and overlapping
physical streams with different batch boundaries.

Endpoint and live-peer tests cover request validation, missing and installed
contexts, path/message mismatch, real response and DATA frames, stream reset,
clean closure, and isolation between streams. A delayed storage test proves
that one stream stops demanding data without stalling another.

Persistence-boundary scenarios disconnect before append, during append, after
durability but before acknowledgement, and after acknowledgement. Reopening
the storage from the same filesystem root proves that server restart resumes
from the durable journal alone.

## Deferred Work

This stage does not implement launch-permit or reconnect-token authentication,
connection generation fencing, connection takeover, durable agent/session
metadata, session ownership, control streams, session reconciliation, command
routing, or the AgentD journal pump.

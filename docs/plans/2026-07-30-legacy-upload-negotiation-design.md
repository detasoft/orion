# Legacy Upload Negotiation Design

## Scope

Implement legacy upload-pack negotiation input after the want phase. The
continuation accepts ordered `have <object-id>` packets, accepts `done`, and
treats flush as a real negotiation-round boundary. Repository reachability and
real ACK selection remain outside this slice.

## Continuation graph

`UploadNegotiationContinuation` owns the accumulated have state. It delegates
pkt-line headers to the shared `ControlHeaderContinuation`; DATA packets move
to a negotiation payload continuation and then return to the shared header
reader.

A flush transitions to an upload negotiation response continuation. That
continuation calls `GitNativeClientOutput.sendNak()` and returns to the same
negotiation owner after output completes. A streaming output result uses
`transitionAndYield`, preserving the flat continuation graph and preventing the
next round from being read before the response is sent.

`done` transitions to `UploadResponseContinuation` with an immutable typed
negotiation result containing the original request and all accumulated haves.

## Grammar and state

Negotiation DATA packets are either:

- `have <40-hex-object-id>`;
- `done`.

A single trailing LF is accepted. No other suffixes or commands are accepted.
Haves are stored in a `LinkedHashSet`, preserving first-seen client order and
deduplicating repeated object IDs across all rounds.

Flush is valid even when the current round contains no new haves. Delimiter and
response-end controls are rejected as typed wire errors.

## Output boundary

Until repository reachability policy exists, every intermediate flush requests
a conservative NAK through `GitNativeClientOutput.sendNak()`. The existing
`sendAck(objectId, status)` method remains the future integration point for
multi-ack negotiation.

Output exceptions become completed continuation errors. Input buffers remain
caller-owned and are never retained by negotiation continuations.

## Tests

Production continuation logic is written before tests, following the repository
rule for `Continuation` implementations. Tests cover fragmented headers and
payloads, ordered deduplication across multiple rounds, the NAK/yield boundary,
`done`, trailing input preservation, malformed object IDs, unsupported
commands, and unsupported controls.

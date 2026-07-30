# Legacy Upload-Pack Request Design

## Scope

Implement the first legacy upload-pack request slice after the v0/v1
advertisement. The slice reads the client's initial `want` phase through its
terminating flush packet, exposes a typed immutable request, and transitions to
an explicit negotiation boundary. It does not generate ACK/NAK responses or a
pack yet.

The implementation includes concrete placeholder continuations for later
negotiation and response work. Those placeholders must terminate with a
descriptive continuation error instead of throwing from `process`.

## Continuation graph

`ControlHeaderContinuation` remains the only continuation that reads pkt-line
headers. It accepts a stage-specific control handler in addition to its
existing initial-request constructor. The handler maps a parsed `ControlState`
to the next continuation without calling that continuation's `process` method.

`UploadRequestContinuation` owns the accumulated want-phase state and acts as
the upload-specific control handler. It first transitions to
`ControlHeaderContinuation`. A DATA header transitions to
`UploadWantPayloadContinuation`, which incrementally reads the declared
payload, updates the request state, and transitions back to a new
`ControlHeaderContinuation` using the same owner. A flush validates the
accumulated request and transitions to `UploadNegotiationContinuation`.

The graph remains flat under the existing `ContinuationRuntime`; no continuation
calls another continuation's `process` method and no nested runtime is added.
Bytes following the flush remain readable and are immediately offered to the
negotiation continuation by the runtime.

## Request model and grammar

The typed request records:

- wanted object IDs in client order without duplicates;
- capabilities from the first `want` line in client order without duplicates;
- the initial service request needed by later repository operations.

The first command must be `want <40-hex-object-id>` and may append
space-separated capability tokens. Later packets in this slice must be
additional `want <40-hex-object-id>` commands without capabilities. Lines may
end in LF, which is removed before parsing. Empty requests, malformed object
IDs, unsupported commands, capabilities on later wants, delimiter packets, and
response-end packets become typed `GitWireError.Kind` failures backed by
`GitGeneralException`.

Shallow and deepen commands are intentionally deferred until their semantics
are implemented. Accepting and then ignoring them would make a clone appear to
succeed with incorrect reachability.

## Negotiation and response boundaries

`UploadNegotiationContinuation` receives the completed typed request and owns
the future `have`/`done` and ACK/NAK exchange. For this slice it is a safe
placeholder that transitions to a descriptive terminal error without consuming
input.

`UploadResponseContinuation` is the corresponding future pack-response
boundary. It is also a safe placeholder and is referenced by the negotiation
boundary so the intended direction of the graph is explicit without adding
repository or output behavior prematurely.

## Error and resource handling

All parsing and validation failures are caught inside `process` and returned as
`ContinuationFlow.completedError`. `ControlHeaderContinuation` keeps only
primitive header state; `UploadWantPayloadContinuation` keeps a byte array for
one bounded pkt-line payload. Neither retains input `ByteBuf` instances, so
they do not own or release caller buffers.

Payload lengths use the existing pkt-line limits. A flush is accepted only
after at least one valid want. Closing a partial continuation relies on runtime
closure because no reference-counted parser state is retained.

## Tests

Production continuation logic is written before tests, following the repository
rule for `Continuation` implementations. Tests cover:

- a complete want plus capabilities followed by flush;
- fragmented header and payload input;
- multiple wants and trailing bytes passed to negotiation;
- malformed object IDs and unsupported commands;
- invalid capabilities on a later want;
- flush before any want;
- placeholder continuations returning terminal errors rather than throwing.

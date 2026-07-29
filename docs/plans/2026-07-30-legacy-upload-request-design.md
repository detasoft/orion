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

`UploadRequestContinuation` coordinates pkt-line framing for the want phase. It
incrementally reads the four-byte hexadecimal header and the declared payload,
so fragmented headers and payloads do not require concatenating input buffers.
After each complete data packet it parses the upload-pack command and continues
with the same instance. On flush it validates the accumulated request and
transitions to `UploadNegotiationContinuation`.

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
response-end packets become typed continuation failures.

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
`ContinuationFlow.completedError`. The continuation keeps only primitive header
state and a byte array for one bounded pkt-line payload; it does not retain
input `ByteBuf` instances and therefore does not own or release caller buffers.

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

# Legacy Upload Native Fetch Request Design

## Goal

After a legacy upload-pack client sends `done`, form a
`NativeFetchRequest` from the exact negotiation state seen by both peers:
requested wants, accumulated haves, and the `thin-pack` and `ofs-delta`
capabilities negotiated between the client and the server advertisement.

This slice stops at constructing the typed request. Calling repository
`fetch` and writing the resulting pack remain later work.

## Server advertisement ownership

`UploadPackContinuation` already owns the `GitV1Advertisement` sent to the
client. It passes that exact immutable advertisement to
`UploadRequestContinuation`, which stores it in `LegacyUploadRequest`.

The continuation chain must not regenerate the advertisement after
negotiation. Repository state or advertised capabilities could have changed,
so only the snapshot actually sent to the client is valid for capability
negotiation.

`LegacyUploadRequest` therefore contains:

- the initial service request;
- the ordered, deduplicated wants;
- the ordered, deduplicated client capabilities;
- the server advertisement sent before the request.

## Request construction

`UploadNegotiationContinuation` continues to collect ordered, deduplicated
haves. When it parses `done`, it creates the final
`LegacyUploadNegotiation` and crosses into `UploadResponseContinuation`.

At that boundary, `UploadResponseContinuation` forms one immutable
`NativeFetchRequest`:

- `wants` comes from `LegacyUploadRequest.wants()`;
- `haves` comes from `LegacyUploadNegotiation.haves()`;
- `done` is `true`;
- `thinPack` is `true` only when both the client capabilities and the saved
  server advertisement contain `thin-pack`;
- `ofsDelta` is `true` only when both sides contain `ofs-delta`.

Capability matching uses the typed `GitCapability` names rather than relying
on object identity or ordering.

## Failure handling

The existing request and negotiation validation remains unchanged. Null
advertisements are rejected at record construction like the other required
state.

Forming the request performs no I/O and introduces no new protocol failure
path. `UploadResponseContinuation.process` remains the unimplemented response
boundary until the repository fetch slice is added.

## Tests

Extend the continuation tests to verify:

- wants and haves are copied exactly into `NativeFetchRequest`;
- capabilities supported and requested by both sides produce `true`;
- a capability present only in the client request produces `false`;
- a capability present only in the server advertisement produces `false`;
- fragmented input still forms the same request after `done`;
- the saved server advertisement is the snapshot passed through the
  continuation chain.

Existing malformed negotiation and intermediate NAK tests remain unchanged.

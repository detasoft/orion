# Streaming Fetch Payload Design

## Goal

Remove whole-packet payload retention from `FetchPayloadContinuation` while
preserving protocol v2 fetch parsing across fragmented input buffers.

## Scope

Only the new protocol v2 fetch continuation changes. The legacy payload
continuations that currently retain byte arrays are outside this correction.

## Design

`FetchPayloadContinuation` owns a small `FetchPayloadParser` state object
instead of a `byte[]`. On every `process` call, it passes readable bytes to the
parser one at a time until the declared pkt-line payload length is exhausted or
the input buffer becomes empty.

The parser recognizes the supported fixed commands and flags incrementally. For
`want` and `have`, it validates and accumulates exactly 40 hexadecimal object-id
digits without retaining the full pkt-line. A final newline remains optional.
Once the declared payload is complete, the parser returns a typed fetch
argument to `FetchContinuation`, which updates request state and transitions
back to `ControlHeaderContinuation`.

Malformed bytes, incomplete tokens, trailing data, and invalid object ids
transition to the existing protocol v2 fetch failure continuation.

## Testing

Existing fetch tests continue to cover complete and fragmented requests. Add a
fragmentation case that splits input inside both a command prefix and an object
id, plus invalid input coverage that demonstrates parsing decisions are
unchanged.

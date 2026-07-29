# Git Initial Request Dispatch Design

## Goal

Replace the temporary `StructuredPayloadContinuation` with explicit dispatch
from a parsed native Git initial request to the continuation for its service
and supported protocol version.

## Dispatch rules

`InitialRequestPayloadContinuation` continues to consume exactly the declared
initial-request payload and produces `InitialRequestData`. A separate dispatch
continuation reads only that parsed metadata:

- `UPLOAD_PACK` with no `version` parameter or `version=1` selects the shared
  protocol v0/v1 upload-pack continuation;
- `UPLOAD_PACK` with `version=2` selects the protocol v2 upload-pack
  continuation;
- `RECEIVE_PACK` with no `version` parameter or `version=1` selects the
  protocol v0/v1 receive-pack continuation;
- `RECEIVE_PACK` with `version=2` is rejected because the current server scope
  does not implement receive-pack protocol v2;
- an empty, `version=0`, or otherwise unknown version is rejected.

The Git transport specification recognizes extra-parameter protocol versions
1 and 2. Absence of the parameter retains the legacy protocol behavior.

## Continuation boundaries

`InitialRequestDispatchContinuation` owns the dispatch decision. Its
`process(ByteBuf)` method does not read from the buffer. It returns a
`Transition` to one of three service continuations:

- `v0v1.UploadPackContinuation`;
- `v2.UploadPackContinuation`;
- `v0v1.ReceivePackContinuation`.

Those protocol implementations are outside this slice. Each is introduced as
a placeholder whose `process` method throws
`IllegalStateException("Not implemented")`.

Version-specific implementations live in protocol-version packages. Versions
0 and 1 share a `v0v1` package while their behavior remains materially the
same. Protocol v2 has its own package because its command-oriented exchange is
substantially different. If implementing the legacy branches later reveals
substantial differences, keep shared mechanics in a small base continuation
and move the variants into separate `v0` and `v1` packages. Do not split them
only for naming symmetry.

Keeping the decision in its own continuation makes the protocol boundary
explicit without consuming input. When an initial request and later protocol
bytes share one `ByteBuf`, the initial-request parser stops at its declared
payload boundary, dispatch leaves the reader index unchanged, and the runtime
passes the same unread bytes to the selected continuation.

## Error handling

Unsupported protocol versions transition to `CompletedError`. The failure
identifies the unsupported version and service rather than entering a fallback
protocol branch. Placeholder protocol continuations throw only when processed;
selecting one during dispatch does not execute it.

## Tests

Focused dispatch tests cover:

- upload-pack with no version and with `version=1`;
- upload-pack with `version=2`;
- receive-pack with no version and with `version=1`;
- receive-pack with `version=2`;
- unknown and empty versions;
- an input buffer with unread bytes whose reader index is unchanged by
  dispatch.

The existing initial-request payload tests are updated to assert transition to
the dispatch continuation rather than the removed temporary continuation.

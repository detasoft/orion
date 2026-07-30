# Legacy Upload Response Design

## Scope

`UploadResponseContinuation` resolves the repository named by the initial
request, builds the negotiated no-delta pack, and writes the legacy
upload-pack response through `GitNativeClientOutput`.

This slice supports only negotiated `side-band-64k`. Other response formats
fail with `IllegalStateException("not implemented")`.

## Negotiated format

The response boundary retains both the typed `NativeFetchRequest` and the
capabilities accepted by both client and server. `side-band-64k` is selected
only when it appears in that intersection. The pack builder flags remain in
`NativeFetchRequest`; wire-format selection remains a parser/wire concern.

## Repository fetch

`GitNativeRepositoryService` resolves the repository from the original request
and returns a stateful `NativePackProducer`. The repository uses
`NativeObjectClosure` to subtract the closure reachable from `have` objects
from the requested closure, then creates the producer through
`NoDeltaPackBuilder`.

The closure returns sorted object IDs rather than inflated `LooseObject`
instances. The producer retains those IDs and the object store, loading only
the current object immediately before its header and deflate data are emitted.
It writes into a caller-owned `ByteBuf` until the buffer is full or the pack is
complete and retains only the current object, serialization cursors, one
`Deflater`, one small compression scratch buffer, and the running SHA-1
digest. It never materializes the complete pack and does not use
`InputStream`, `OutputStream`, or another Java stream API.

## Output channels

`GitNativeClientOutput` exposes a typed side-band channel:

- `DATA` (`1`) for pack bytes;
- `PROGRESS` (`2`) for informational messages;
- `ERROR` (`3`) for fatal messages.

The output serializes channel data as `side-band-64k` pkt-lines. Every packet
contains a four-byte pkt-line header, one channel byte, and at most 65,515 data
bytes. The response is `NAK`, followed by channel-1 pack packets and a final
flush packet.

Each `Streaming` task submits at most one outbound buffer. When that network
write completes, `resumeTask()` processes the same response continuation
again. The output then asks the producer for the next pack bytes and returns
another `Yield` when another outbound chunk is ready. This preserves network
backpressure rather than generating the whole response inside one task.

## Continuation flow

`UploadResponseContinuation` creates the producer once and retains it across
resumes. Each `process` call advances the side-band response once. An
intermediate `Streaming` result transitions back to the same continuation and
yields one submission task. `Completed` closes the producer and ends the
conversation successfully. `Failed` closes the producer and becomes the
standard terminal error continuation.

## Tests

Builder tests reconstruct its fragmented output and verify exact pack bytes,
fragmentation at every boundary, deterministic object ordering, and closure.
Output tests cover channel byte encoding, maximum packet fragmentation, final
flush, one-submission-per-task behavior, and all three typed channels.
Continuation tests cover repeated Yield/resume, successful completion, and the
unsupported-format exception path.

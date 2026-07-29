# Legacy Upload-Pack Advertisement Design

## Goal

Implement the protocol v0/v1 upload-pack advertisement behind
`v0v1.UploadPackContinuation`. The continuation resolves an automatically
created in-memory native repository, builds its legacy advertisement, and
passes the typed value to `GitNativeClientOutput`.

## Repository Access

`GitMinimalWireMachine.Context` exposes an
`InMemoryNativeGitRepositoryProvider` alongside the allocator and client
output. The upload-pack continuation resolves the repository from the initial
request path with `findOrCreate`.

`NativeGitRepository` exposes only the repository state needed for this slice:
the default HEAD target and a snapshot of refs. Other server operations may
remain explicit `IllegalStateException("not implemented")` placeholders until
their protocol continuations are implemented.

The continuation converts the repository snapshot into a deterministic list
of `GitAdvertisedRef` values. It advertises HEAD when its target exists, sorts
the remaining refs by name, and uses the legacy upload-pack capability set.
An empty repository uses Git's zero-object-id `capabilities^{}` pseudo-ref so
the first advertisement line can carry capabilities.

## Streaming Client Output

`GitNativeClientOutput` owns serialization progress for an outbound operation.
The continuation does not retry or perform chunk-level serialization.

`sendAdvertisement` starts serializing a typed `GitV1Advertisement` into the
fixed outbound buffer and returns one of two results:

- completed, when the complete value fits in the current buffer;
- streaming, containing a task when serialization reaches the buffer limit.

For a streaming result, the output retains the original advertisement and an
exact serialization cursor. The returned task sends the current buffer,
continues serialization from that cursor, sends each subsequent full buffer,
and finally schedules the last partial buffer for sending. It clears the
retained operation only after the whole advertisement has been serialized and
all produced buffers have been submitted to the client.

Only one streaming operation may be active. Starting another operation while
one is active fails with `IllegalStateException`.

The send task is the task carried by `ContinuationFlow.Yield`. The runtime
resumes only after the task has serialized the complete object and submitted
all of its output. Transport or serialization failures escape the task and
follow the existing Yield task failure path.

If serialization completes without filling the current buffer,
`sendAdvertisement` returns completed and leaves the encoded bytes in the
normal outbound buffer for the transport's ordinary drain policy.

## Continuation Flow

`v0v1.UploadPackContinuation` performs these steps:

1. resolve or create the requested repository;
2. snapshot refs and HEAD;
3. build the typed legacy advertisement;
4. call `GitNativeClientOutput.sendAdvertisement`;
5. transition immediately for a completed result;
6. return `ContinuationFlow.Yield` with the streaming send task otherwise.

The continuation does not repeat `sendAdvertisement` after the Yield. The
next upload-pack request stage is introduced as an explicit
`IllegalStateException("not implemented")` continuation placeholder if the
existing graph has no suitable continuation yet.

## Testing

Production continuation logic is implemented before its tests, as required
for `Continuation` classes in this repository.

Focused tests cover:

- a populated repository advertisement, including HEAD and deterministic ref
  order;
- an empty repository advertisement with the zero-object-id pseudo-ref;
- provider access through `GitMinimalWireMachine.Context`;
- streaming across multiple fixed-buffer sends without duplicate or missing
  bytes;
- preservation and cleanup of the streaming cursor;
- rejection of a concurrent output operation;
- propagation of the real send task through `ContinuationFlow.Yield`.


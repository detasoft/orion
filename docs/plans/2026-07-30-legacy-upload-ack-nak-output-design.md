# Legacy Upload ACK/NAK Output Design

## Scope

Extend `GitNativeClientOutput` with typed legacy upload-pack negotiation
responses. The output supports `NAK` and every legacy ACK form while preserving
the existing `Completed`/`Streaming` contract used by continuations to decide
between an immediate transition and `Yield`.

This slice only adds output serialization. It does not implement negotiation
policy, choose an object to acknowledge, or connect the new operations to
`UploadNegotiationContinuation`.

## Public API

Add:

```java
SendResult sendNak()

SendResult sendAck(GitObjectId objectId, AckStatus status)
```

`AckStatus` is a typed enum with:

- `FINAL`, encoded without a wire suffix;
- `CONTINUE`, encoded with `continue`;
- `COMMON`, encoded with `common`;
- `READY`, encoded with `ready`.

The object ID remains typed as `GitObjectId`; callers cannot pass an invalid
wire identifier. Null arguments are rejected before output state changes.

## Wire encoding

Each response is one pkt-line:

```text
0008NAK\n
0031ACK <40-hex-object-id>\n
003aACK <40-hex-object-id> continue\n
0038ACK <40-hex-object-id> common\n
0037ACK <40-hex-object-id> ready\n
```

The pkt-line length is calculated from encoded bytes rather than embedded as a
separate protocol constant.

## Buffering and completion

Advertisement, ACK, and NAK operations use one internal resumable
serialization path. An operation writes as much as possible into the
caller-owned fixed 64 KiB output buffer.

If the complete operation fits, it returns `Completed` and leaves the bytes in
the output buffer. If the buffer fills before completion, it retains the
operation and returns `Streaming(task)`. Running the task submits the buffered
prefix, clears the output buffer, writes the remainder, and submits the final
bytes. A continuation maps `Streaming` to `Yield` using the returned task.

The same behavior applies when the buffer is already full before an ACK or NAK
starts: the operation returns `Streaming` without losing the previously
buffered bytes. Only one serialization operation may be active. Completion or
send failure releases that operation so a later send may proceed.

## Error handling

Encoding and argument validation happen before an operation is installed as
active. Send failures propagate from the streaming task. The submitted copy is
released on callback failure, the caller-owned output buffer is cleared through
the existing submission path, and the active operation is released in all
cases.

## Tests

Extend `GitNativeClientOutputTest` to cover:

- exact NAK pkt-line encoding;
- exact ACK encoding for `FINAL`, `CONTINUE`, `COMMON`, and `READY`;
- `Completed` when a response fits;
- `Streaming` when a partially filled buffer cannot hold the response;
- `Streaming` when the output buffer is already full;
- preservation and ordered submission of existing buffered bytes plus the
  negotiation response;
- rejection of a concurrent operation until its streaming task completes.

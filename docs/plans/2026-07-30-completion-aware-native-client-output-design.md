# Completion-Aware Native Client Output Design

Status: superseded by
`docs/plans/2026-08-31-blocking-git-native-client-output.md`.

The asynchronous ownership model below is historical. Native Git sessions now
use blocking `BufferedByteOutput` writes on virtual threads, so neither the
double-buffer coordinator nor the proposed ring buffer is current architecture.

## Goal

Replace per-send output copies with a completion-aware buffering contract that
keeps serialized bytes immutable while an asynchronous client write owns them.
Introduce a double-buffer implementation first and preserve an implementation
boundary for a later ring buffer.

## Requirements

`GitNativeClientOutput` must:

- serialize typed Git responses without allocating one independent copy for
  every submitted chunk;
- never overwrite bytes until the client write completion confirms that the
  corresponding memory is no longer in flight;
- apply back-pressure when every writable region is in flight;
- resume the continuation runtime only after the complete typed value has been
  serialized and all writes required by that operation have completed;
- propagate synchronous submission failures and asynchronous completion
  failures through the Yield task;
- allow only one streaming serialization operation at a time;
- release every pooled buffer exactly once on success, failure, cancellation,
  and close;
- preserve byte-for-byte pkt-line output across buffer boundaries;
- keep continuation code independent of buffer count and layout.

The first implementation must use double buffering. A ring buffer follows as a
separate task after the common contract and transport integration are proven.

## Client Write Contract

The output depends on a transport-neutral asynchronous write port:

```java
interface GitNativeClientWrite {
    CompletionStage<Void> write(ByteBuf ownedBuffer);
}
```

Calling `write` transfers temporary ownership of the readable buffer to the
client write. The buffer remains immutable until the returned stage completes.
The output implementation retains ownership of buffer allocation and releases
or recycles the buffer after completion.

The port must define synchronous throw and exceptional completion identically:
both fail the active output operation. A successful stage means the buffer can
be reclaimed; it does not necessarily mean the remote peer has processed the
bytes.

## Output Operation

Typed output methods keep the current result shape:

```java
sealed interface SendResult {
    record Completed() implements SendResult {}
    record Streaming(Runnable task) implements SendResult {}
}
```

`Completed` means the complete value was serialized without requiring the
runtime to suspend. Buffered bytes may still be handled by the transport's
ordinary drain policy.

`Streaming.task` owns the rest of the operation. It serializes remaining data,
submits buffers, waits for required write completions when no writable region
is available, and returns only when the complete value is serialized and all
writes submitted by the task have completed. The runtime resumes afterward.

The internal serializer retains the original typed value and a precise cursor.
Continuation code never repeats a typed send method after the streaming task
finishes.

## Double Buffer

The first implementation owns two fixed-size pooled `ByteBuf` instances. Each
buffer has one state:

- `WRITABLE`: available to the serializer;
- `READY`: contains serialized bytes not yet submitted;
- `IN_FLIGHT`: immutable until its write stage completes;
- `CLOSED`: released and unavailable.

The serializer writes to the current `WRITABLE` buffer. When it fills:

1. mark it `READY`;
2. submit it and mark it `IN_FLIGHT`;
3. switch to the other `WRITABLE` buffer;
4. if the other buffer is not writable, await the earliest reclaimable
   completion before continuing.

The final partial buffer is submitted when a streaming operation finishes.
The task awaits every write that it submitted before returning. Completion
callbacks reclaim buffers on the output coordinator's serialized execution
path; they do not mutate serializer state concurrently.

Two buffers permit serialization and one asynchronous write to overlap without
copying. They do not allow unbounded queueing: when both buffers are in flight,
the serializer stops.

## Ring Buffer Extension

The later ring implementation uses one pooled memory region and logical
positions:

- `reclaim`: first byte whose completed range may be reused;
- `read`: first ready byte not yet submitted;
- `write`: next serialization position;
- an ordered queue of in-flight ranges and their completion states.

Ready data is submitted as one contiguous slice, or as two slices when it
crosses the end of the region. Submitted ranges remain immutable. Completed
ranges are reclaimed only from the head of the in-flight queue, so out-of-order
write completion cannot open a hole that the serializer overwrites early.

The ring reports no writable capacity when advancing `write` would enter ready
or in-flight data. The serializer then waits for head reclamation.

The common typed serializer and `GitNativeClientWrite` port are reused. Only
the storage coordinator changes.

## Error Handling and Close

On serialization or write failure:

- stop producing new bytes;
- preserve the first failure;
- await or cancel outstanding writes according to the transport contract;
- release buffers after their ownership returns;
- clear the active typed value and cursor;
- complete the Yield task exceptionally.

`close` prevents new operations. It cancels the active operation, waits for or
arranges cleanup of in-flight ownership, and releases writable buffers. Repeated
close calls are idempotent.

## Testing

Contract tests shared by double and ring implementations cover:

- a value fitting without Yield;
- multiple full-buffer cycles with byte-for-byte output;
- the final partial buffer;
- synchronous write failure;
- asynchronous exceptional completion;
- back-pressure while all regions are in flight;
- no mutation of in-flight bytes;
- concurrent-operation rejection;
- cleanup after failure and close;
- exact-once buffer release.

Double-buffer tests additionally cover alternating buffers and both buffers in
flight.

Ring-buffer tests additionally cover:

- wrap-around serialization and submission;
- one- and two-slice ready ranges;
- a completely full ring;
- out-of-order write completion;
- ordered reclamation;
- failure and close with wrapped in-flight ranges.

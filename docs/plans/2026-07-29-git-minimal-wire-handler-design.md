# Git Minimal Wire Handler Design

## Goal

Adapt `GitMinimalWireHandler` to the `RuntimeFlow` contract of
`GitMinimalWireMachine` and verify it with `EmbeddedChannel`, without starting
a Netty server or connecting the handler to application lifecycle.

## Responsibilities

`GitMinimalWireMachine` owns Git protocol state and its continuation runtime.
`GitMinimalWireHandler` owns Netty input references, schedules yielded tasks,
and maps runtime outcomes to channel behavior.

The handler keeps at most one retained input while a yield is pending. The
machine and `ContinuationRuntime` never retain or release transport input.

## Flow Handling

- `Await` releases the current transport input and waits for another
  `channelRead`.
- `Yield` retains the current input, schedules the yielded task on the channel
  executor, and calls `resumeTask` after the task completes. Consecutive yields
  reuse the same retained reference and are scheduled iteratively.
- `Terminal` means the conversation cannot accept more input. The handler
  closes the machine and channel. If the terminal state exposes a failure, the
  handler reports it through the channel pipeline before closing.
- `RuntimeFlow.Error` is a non-terminal runtime contract violation. The handler
  logs it and leaves the machine and channel usable.
- An exception escaping the machine or a yielded task is treated as an
  unrecoverable adapter failure: report it, close the machine, and close the
  channel.

The handler does not synthesize a Git `ERR` pkt-line for terminal failures.
Doing so requires conversation-phase knowledge that does not belong in this
minimal transport adapter.

## Input During Yield

If `channelRead` receives the same `ByteBuf` object that is already retained
for a pending yield, the handler does not call `accept` again. Any newly
appended bytes remain visible when the machine resumes with that object.

If it receives a different `ByteBuf`, the current temporary policy is to log a
warning, release the new buffer, and keep the current yield alive.

The implementation must contain a TODO explaining that dropping the distinct
buffer is temporary. A separate task must choose a durable inbound strategy,
such as a queue, explicit cumulation, or sequential submission after the
current yield finishes. Receiving a distinct buffer during yield is valid
transport behavior, not a reason to terminate the machine.

## Closing

`channelInactive` closes the machine and releases any retained yield input.
Already scheduled tasks must observe the closed state and perform no resume.
Cleanup is idempotent.

## Tests

Tests use only `EmbeddedChannel` and cover:

- ordinary fragmented pkt-line input and caller-owned reference release;
- one and several consecutive yields;
- terminal completion closing the channel;
- non-terminal `RuntimeFlow.Error` preserving usability;
- yielded-task failure;
- channel closure while a yield is pending;
- the same input object reappearing during yield;
- a distinct input object during yield being logged/dropped without closing.

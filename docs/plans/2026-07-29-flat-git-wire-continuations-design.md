# Flat Git Wire Continuations Design

## Goal

Replace the nested child-runner hierarchy introduced in `937f245` with one
flat graph of `Continuation<ByteBuf>` objects driven exclusively by one
`ContinuationRuntime<ByteBuf>`.

## Structure

The runtime starts with `HeaderContinuation`, because the first bytes of every
pkt-line are its four-byte header. Each processing stage is a separate class in
`pro.deta.orion.git.parser.wire.continuation`.

The normal pkt-line path is:

```text
HeaderContinuation
  -> ControlDispatchContinuation
  -> PayloadContinuation
  -> DispatchContinuation
  -> HeaderContinuation
```

Semantic parsing skips callback-only control dispatch and proceeds from the
header to the payload. Non-data control packets proceed directly from the
header to dispatch with an empty payload.

Dispatch may instead transition to raw forwarding, side-band decoding, success,
or error. No continuation calls another continuation's `process` method.
`ContinuationRuntime` is the only component that follows transitions and
repeats processing with the current input.

## Lifecycle and errors

`ChildRunner` and `ChildStep` are removed. A continuation transfers all state
needed by its successor through the successor's constructor. Runtime
`transitionTo` closes the previous continuation after installing the next one.
Any owned `ByteBuf`, decoder, or raw target therefore has one explicit owner
and is either transferred before transition or released by `close`.

Continuation processing reports failures only by returning a transition to
`Continuation.completedError(message, throwable)`. The runtime exposes every
terminal continuation, including `CompletedError`, as
`ContinuationFlow.Terminal`; callers inspect `current` to distinguish the
terminal kind. `ContinuationFlow.Error(message, throwable)` is non-terminal
and reports only a runtime API contract violation without changing `current`.
An unexpected exception escaping `process()` is an invariant violation and is
deliberately not caught because runtime state can no longer be assumed valid.

## Compatibility boundary

`GitMinimalWireMachine` remains the public facade and owns the runtime, semantic
flow, and Yield exposure. It has no protocol-response-specific factories:
advertisement, ls-refs, and fetch parsing are stages to compose inside a
full-conversation machine rather than separate machine instances. Existing
tests are deliberately left unchanged during this architecture pass, even
where package-private implementation tests temporarily stop compiling. Test
scenarios will be redesigned after the production graph is complete.

The facade uses `ContinuationRuntime<ByteBuf>` directly. The runtime remembers
the input value needed for resume but does not own or retain it. Netty
reference-count ownership stays entirely in `GitMinimalWireHandler`: it retains
the input across the first Yield, carries that reference through consecutive
Yields, and releases it after the final resume, failure, or close. The facade
does not cache a Yield task: `accept` and `resumeTask` return the runtime's
`ContinuationFlow<ByteBuf>` directly, and the adapter schedules the task from
the returned `Yield`.

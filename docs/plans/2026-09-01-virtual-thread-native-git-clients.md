# Virtual-Thread Native Git Clients

## Decision

Implement native upload-pack and receive-pack clients as blocking sessions on
virtual threads. Do not introduce a protocol state machine, client action graph,
or continuation runtime for outbound Git operations.

This matches the current native server architecture: protocol code reads from
`BufferedByteInput`, writes to `BufferedByteOutput`, and relies on blocking I/O
for bounded backpressure. The operating system and transport implementation own
socket readiness; the virtual thread owns the sequential protocol conversation.

## Architecture

- Keep upload-pack and receive-pack as separate typed client operations.
- Run one remote operation per virtual thread.
- Reuse `GitBlockingWireTransport` framing and serialization where client and
  server wire behavior is identical; add client-specific blocking codecs where
  protocol direction differs.
- Represent advertisement, negotiation, pack transfer, and report-status as
  ordinary methods and loops in wire order.
- Return typed success and failure results without exposing transport buffers or
  protocol phases to callers.
- Make cancellation close the transport and interrupt or unblock the session.
- Apply connect, read, write, and overall-operation timeouts at the transport or
  session boundary.

`GitBlockingWireSession` remains the server-side conversation and should not be
stretched into a bidirectional abstraction. Share framing, validation, and
serialization helpers rather than server control flow.

## Transport Boundary

A client transport opens a closeable session containing blocking byte input and
output. SSH and Smart HTTP adapters may have different connection and request
lifecycle rules, but both present the same byte contracts to the protocol
client.

Writes are complete when the blocking output accepts them according to its
contract. No completion-aware double buffer or ring-buffer coordinator sits
between the protocol and transport. Add buffering only if profiling identifies
a concrete bottleneck and keep it inside the blocking transport adapter.

## Delivery Order

1. Add shared client result, request, and transport-session contracts.
2. Implement blocking upload-pack discovery, negotiation, and pack reception.
3. Implement blocking receive-pack advertisement, command submission, pack
   upload, and report-status handling.
4. Add SSH and Smart HTTP adapters on virtual threads.
5. Add end-to-end fetch and push compatibility coverage against canonical Git
   and the Orion native server.

## Verification

Cover successful fetch and push plus fragmented input, large pack transfer,
early EOF, protocol rejection, authentication failure, timeout, cancellation,
and transport close. Verify that buffers are released exactly once and that a
blocked write applies backpressure without a protocol-side queue.

# Blocking Git Parser I/O Design

## Context

The native Git server path is moving away from `Continuation` as the normal
protocol execution model. The target model is a blocking, linear parser that can
run on virtual threads. Backpressure should block the session thread instead of
being represented as continuation yield/resume flow.

The existing `GitByteBufTransportAdapter` already exposes blocking public
methods for SSH and HTTP callers:

- `advertise(...)`
- `serveCommand(...)`
- `serveSmartHttpPost(...)`

The remaining continuation dependency is mostly inside the parser state graph:
pkt-line header continuations, payload continuations, command dispatch
continuations, and response continuations.

## Decision

Replace the production Git parser path with direct blocking byte I/O. Do not add
a compatibility layer that wraps continuations. The parser should read from
`BufferedByteInput`, write through `BufferedByteOutput` via
`GitNativeClientOutput`, and use ordinary method calls and loops for protocol
state.

Timeouts are byte I/O properties:

- `BufferedByteInput` implementations block until bytes are available, EOF is
  reached, or the configured read timeout expires.
- `BufferedByteOutput` implementations block until bytes are accepted/flushed or
  the configured write timeout expires.
- The Git parser does not own timers and does not retry timed-out I/O.
- Read and write timeouts are surfaced to the parser as `IOException`.

## EOF And Timeout Semantics

EOF before a new protocol unit may be accepted only when the current Git
protocol state allows the session to end.

EOF inside a pkt-line header or payload is an error. A partial header or payload
must wait for more bytes until the input timeout fires or EOF is reported by the
transport.

Read timeout inside a header or payload is an error.

Write or flush timeout is an error and terminates the Git session. The transport
owner decides how to log, close, or report that failure.

## Parser Shape

Keep the current public transport adapter entry points initially, but replace
their internals with a blocking Git session runner.

The runner owns:

- initial request advertisement flow;
- smart HTTP POST flow after advertisement has already happened;
- SSH command flow where the initial request data is provided by the SSH command
  line;
- protocol v0/v1 upload-pack negotiation and response;
- protocol v0/v1 receive-pack command parsing and pack ingestion;
- protocol v2 command parsing, `ls-refs`, and `fetch`.

Pkt-line reading should use blocking primitives:

```java
ControlState control = pkt.readControlState();
ByteBuf payload = pkt.readPayload(control);
```

Payload copies returned to repository or ingestion workers must be owned buffers
whose lifetime is independent from the connection input buffer.

## Receive-Pack

Receive-pack ingestion stays streaming. The blocking runner reads chunks from
`BufferedByteInput` and feeds `PackIngestionSession` until it returns complete or
failed.

The parser thread is allowed to block waiting for more pack bytes. If the client
stalls during pack upload, `BufferedByteInput` times out and the session fails
with `IOException`.

## Output

`GitNativeClientOutput` remains the central Git response writer. Its writes are
blocking because its sink is `BufferedByteOutput`.

Large pack responses continue to advance producer output in chunks. When a
transport cannot accept bytes immediately, `BufferedByteOutput.write(...)` or
`flush()` blocks. On virtual threads this parks the session thread and naturally
models backpressure.

## Tests

Tests that currently drive continuations one byte at a time should move to a
blocking session test model.

Use queue-backed test byte I/O:

- the parser runs on a separate thread;
- the test thread submits bytes one at a time or in selected fragments;
- `read...` blocks when the queue is empty;
- test timeouts are short and deterministic;
- output is recorded through a blocking `BufferedByteOutput` test adapter.

This preserves fragmentation coverage without asserting continuation transition
details.

## Migration Slices

1. Add blocking queue-backed test input/output adapters with deterministic
   timeout behavior.
2. Document blocking timeout contracts on `BufferedByteInput` and
   `BufferedByteOutput`.
3. Introduce the blocking Git session runner behind the existing
   `GitByteBufTransportAdapter` public API.
4. Move initial request, advertisement, and protocol v2 `ls-refs` onto the
   blocking runner.
5. Move protocol v2 `fetch`.
6. Move legacy upload-pack.
7. Move legacy receive-pack and pack ingestion.
8. Remove production dependencies on Git wire continuations after all callers use
   the blocking runner.

## Non-Goals

Do not remove the shared `core/common-runtime` continuation framework as part of
this parser migration.

Do not change SSH or HTTP public routing behavior in the first parser slice.

Do not introduce no-copy shared input slices for worker payloads. Worker-owned
payloads should stay copied until profiling proves that a page/lease model is
needed.

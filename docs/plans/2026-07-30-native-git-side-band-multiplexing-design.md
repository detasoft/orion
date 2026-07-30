# Native Git Side-Band Multiplexing Design

## Goal

Allow one legacy `side-band-64k` response to interleave pack `DATA` with
ordered `PROGRESS` and `ERROR` messages while retaining one output buffer, one
outbound transport, and the existing yield-based backpressure.

## Response API

`GitNativeClientOutput.LegacySideBandResponse` owns the complete response.
Callers add informational and error messages through `progress(ByteBuf)` and
`error(ByteBuf)`. The response copies each message when it is accepted so
later caller mutations cannot change queued wire data.

`ERROR` is a normal ordered channel message. It does not close the response,
stop the pack producer, or prevent later `DATA`, `PROGRESS`, or `ERROR`
messages.

The response is single-thread confined, matching the wire continuation that
owns it. Concurrent calls from other threads are outside the contract.

## Multiplexing

The fixed 64 KiB `ByteBuf` is a staging buffer rather than storage for the
whole response. The response writes consecutive pkt-line frames into it:

```text
[four-byte length][one-byte channel][payload]
```

Each frame has one channel. A frame already being serialized is completed
before another channel is selected. Messages accepted after an emitted data
frame enter a FIFO and are emitted before the next pull from the pack
producer. Multiple queued messages retain their acceptance order.

Large messages and pack output are split across multiple legal
`side-band-64k` frames. The response retains the current message and its byte
offset across output-buffer submissions.

## Backpressure and Completion

`advance()` fills the shared staging buffer until it cannot serialize another
frame or reaches a response boundary. When bytes are readable it returns one
`SendResult.Streaming`; that task performs exactly one submission through the
existing `sendToClient` transport and clears the staging buffer. A later
continuation resume calls `advance()` again.

The pack producer is pulled only when there is no queued message. This keeps
message ordering deterministic and prevents eager pack production while an
outbound write is pending.

When the pack producer completes, the response drains messages already
accepted by the response and then writes the terminal flush pkt-line. Closing
the response releases both the pack producer and any queued message copies.

## Failures

Expected message validation, serialization, and delivery failures use the
existing output result and continuation flow. Serialization failures close the
response and return `SendResult.Failed`. Programmer contract violations such
as use after close retain the current failed-result behavior.

## Tests

Focused output tests cover:

- `DATA`, `PROGRESS`, `DATA`, `ERROR`, and later `DATA` on one response;
- FIFO ordering for multiple progress and error messages;
- continued pack production after `ERROR`;
- message fragmentation at the pkt-line payload limit;
- one outbound submission per streaming task;
- queued-buffer release on close and serialization failure.

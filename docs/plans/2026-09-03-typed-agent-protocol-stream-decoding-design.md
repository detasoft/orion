# Typed Agent Protocol Stream Decoding Design

## Goal

Give AgentD one bounded, typed CBOR Sequence decoder whose behavior does not
depend on HTTP/2 DATA chunk boundaries. A bad message whose complete CBOR
boundary is known is logged and skipped. The affected connection or logical
stream is ended only when the parser cannot establish the next boundary within
the configured structural limits.

## Current Problem

The same inbound bytes currently pass through two incremental implementations:

```text
Jetty ByteBuffer
  -> agentd.transport.CborSequenceDecoder
  -> copied byte[] item
  -> AgentControlService / a future session consumer
  -> AgentProtocolCodec
  -> AgentMessage
```

`agentd/src/main/java/pro/deta/orion/agentd/transport/CborSequenceDecoder.java`
owns one CBOR boundary scanner, while
`agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborSequenceBuffer.java`
owns another. The latter first creates `combined`, copies every complete item,
and only afterward lets `AgentProtocolDecoder` or `SessionEventDecoder` decode
the collected batch.

That separation creates two observable problems:

- a valid item before a bad item can be consumed but not returned when both
  arrive in one call;
- every item is copied just to cross the boundary between sequence splitting
  and semantic decoding.

Jetty supplies `java.nio.ByteBuffer` and releases its `Stream.Data` immediately
after the receive callback. The decoder must therefore finish all parsing and
create owned typed values synchronously before that release. There is no Netty
`ByteBuf` in this path, so introducing one would add an unrelated dependency
and reference-counted lifetime model.

## Ownership and Typed Contract

`agent-protocol` becomes the only owner of incremental CBOR Sequence boundary
parsing. The AgentD `CborSequenceDecoder` and the protocol module's current
all-items-first `CborSequenceBuffer` are deleted.

One package-private generic sequence engine is shared by the two public typed
facades. It delegates all incremental boundary recognition to one scanner in
`agent-protocol`; codecs may still interpret the fields inside an already
bounded item, but no second sequence splitter or boundary state machine remains:

- `AgentProtocolDecoder` produces `AgentMessage`;
- `SessionEventDecoder` produces `SessionEventRecord`.

Both facades accept a `ByteBuffer`. Their result contains ordered per-item
outcomes, each holding either a successfully decoded value or a recoverable
rejection, plus an optional terminal structural failure. It must be possible to
return a valid prefix together with a later terminal failure; a checked
exception must not erase that prefix. After a terminal failure the decoder is
poisoned until it is reset or discarded with its stream.

A recoverable rejection carries the `AgentProtocolException` reason, a safe
diagnostic, and the encoded item size. It does not retain or print the raw CBOR
item. This gives the transport enough context to log the problem without
leaking input, terminal content, credentials, or future opaque fields.

The stream engine determines a complete structural span first and invokes its
typed item decoder immediately for that span. An exception from the typed item
decoder is recoverable because the end of that item is already known. An
exception from the structural scanner is terminal because there is no trusted
place from which to resume.

## Failure Classification

| Input condition | Decoder result | AgentD action |
| --- | --- | --- |
| Incomplete but potentially valid item | Retain the tail and wait | Keep the stream open |
| Complete and valid known item | Typed value | Deliver it |
| Complete valid item with an unknown type or payload | Typed opaque value | Deliver it unchanged |
| Complete item that fails semantic decoding | Recoverable rejection | Log at error, skip it, continue |
| Structurally malformed item | Terminal failure after any valid prefix | End the affected stream/connection |
| Item cannot be bounded within structural limits | Terminal failure after any valid prefix | End the affected stream/connection |
| End of stream with an incomplete item | Terminal truncation | Report failure while ending the stream |

Semantic failures include missing or invalid known fields, invalid UTF-8,
duplicate known map keys, known-field size violations, and unsupported encoded
versions. They are recoverable at the sequence layer because the scanner has
already established the next item boundary. Whether a decoded message is
allowed in the current authentication or application state remains a separate
state-machine decision.

The structural limits are the hard resource envelope for finding a boundary.
For example, if the current item fills `maxMessageBytes` and remains incomplete,
or nesting exceeds the configured structural depth, the decoder does not keep
buffering in an attempt to find a later item. It reports a terminal failure.

No heuristic scan for a plausible next CBOR initial byte is allowed. Almost
every byte can begin a CBOR item, so such recovery could silently reinterpret
payload bytes as protocol messages.

## Buffer and Copy Model

The sequence engine owns one reusable bounded buffer and start/scan/end
indices. It copies from the borrowed Jetty `ByteBuffer` only while the receive
callback owns it, scans complete items incrementally, decodes each item from a
bounded range in that same buffer, and compacts only the incomplete tail.

The implementation must not create a `combined` array for every `accept` call
or a standalone `byte[]` for every complete item. Coalesced small items may
exceed `maxMessageBytes` in total; the engine processes and discards completed
items as it fills the buffer so memory remains bounded by one in-progress item.

Codec internals gain range-aware entry points so the typed decoder can read an
item directly from the owned buffer. Copies remain only where a returned value
must own bytes beyond the next buffer reuse:

- byte fields of known messages retain their normal defensive ownership;
- `AgentMessage.Unknown` retains one copy of the complete encoded item;
- `SessionEventRecord` retains the bytes required by its existing opaque
  forwarding contract.

Removing the separate `encodedPayload` copy from `SessionEventRecord` and
collapsing every semantic CBOR representation are broader follow-up work. This
task removes transient sequence/framing copies without weakening the public
byte-ownership contract.

## AgentD Integration

Both server-to-AgentD response paths contain Agent protocol messages:

- the control response stream carries `WELCOME` and server commands;
- each session response stream carries `SESSION_SYNC`.

Accordingly, every connection generation and session response stream owns an
`AgentProtocolDecoder`. `AgentTransport` replaces raw inbound callbacks with
typed `AgentMessage` callbacks. `AgentControlService` no longer decodes a raw
`byte[]`; it receives an `AgentMessage` directly. Outbound methods remain byte
oriented because session request DATA also carries byte-for-byte journal
records after `SESSION_OPEN`.

AgentD logs every recoverable rejection at error level with stream kind,
optional session ID, protocol reason, and item length, but never raw bytes. It
continues demanding DATA and delivering later typed messages. Unknown protocol
messages are successful opaque values, not errors.

A batch containing valid messages followed by a terminal structural failure is
handled as one ordered callback unit: deliver the valid prefix first, then
invalidate the generation or reset the session stream. A control-stream
structural failure ends the connection. A session-response structural failure
resets only that logical session stream. Replacement of a generation may still
discard callbacks belonging to that obsolete generation.

## Compatibility

The wire remains the existing unframed CBOR Sequence. No marker, length prefix,
checksum, or block envelope is added. Definite and valid indefinite CBOR forms
remain readable, and checked-in v1 fixtures must continue to decode and
re-encode according to their existing contracts.

This change deliberately alters error handling: semantic rejection no longer
disconnects the transport, and successfully decoded items before a later
failure are no longer lost merely because they shared a DATA chunk.

## Verification

Protocol tests cover direct and fragmented `ByteBuffer` input, coalesced items,
chunk-boundary invariance, valid/invalid/valid sequences, valid-prefix plus
terminal structural failure, truncation at finish, reset, bounds, and opaque
byte preservation.

AgentD live-peer tests send real encoded `AgentMessage` values rather than
arbitrary CBOR examples. They prove that a semantic rejection is reported and
skipped while the same stream delivers a later message, and that structural
loss ends only the affected connection or session response stream after first
delivering any valid prefix.

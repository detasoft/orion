# Module Review: `agent-protocol`

### 6. Fragmented indefinite-length CBOR strings can spin forever

**Problem.** `CborItemScanner.scan` repeatedly calls `scanStringChunk` while an indefinite-length byte or
text string is open. If the current chunk header or body is incomplete, `scanStringChunk` returns `INCOMPLETE`
without advancing `position`, but `scan` immediately continues the same loop instead of returning to its
caller. For example, the valid unknown control item `82 18 63 5f 42 01 02 ff` hangs when its first fragment is
`82 18 63 5f 42 01`. The same no-progress loop can occur when an item crosses the parser's internal buffer
boundary, trapping a transport or journal reader thread rather than waiting for more bytes.

**Sources.** The loop is in
[`CborItemScanner.scan`](src/main/java/pro/deta/orion/agent/protocol/CborItemScanner.java#L52); the two
no-progress returns are in
[`scanStringChunk`](src/main/java/pro/deta/orion/agent/protocol/CborItemScanner.java#L146). The scanner is fed
through the 8 KiB incremental buffer in
[`CborSequenceParser`](src/main/java/pro/deta/orion/agent/protocol/CborSequenceParser.java#L10). Real callers
run it on the server control ingress in
[`AgentControlRoute`](../net/http-core/src/main/java/pro/deta/orion/transport/http/AgentControlRoute.java#L125),
the AgentD HTTP/2 receive path in
[`JettyHttp2Transport`](../agentd/src/main/java/pro/deta/orion/agentd/transport/JettyHttp2Transport.java#L442),
the AgentD journal reader in
[`FileSystemSessionJournalReader`](../agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java#L385),
and server journal recovery in
[`SegmentReader`](../agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java#L817).
Existing fragmentation coverage uses ordinary definite-length values and misses this state in
[`AgentProtocolDecoderTest`](src/test/java/pro/deta/orion/agent/protocol/AgentProtocolDecoderTest.java#L91).

**Documented behavior.** The [protocol specification](protocol/README.md#L3) permits a DATA frame to split an
item at any byte and permits valid indefinite containers. The
[stream-decoding design](../docs/plans/2026-09-03-typed-agent-protocol-stream-decoding-design.md#L156) preserves
those forms and requires incomplete input to wait for later data.

**Contract.** Fragmentation must not affect decoding. A valid but incomplete CBOR item remains pending without
busy-waiting, while the same item must decode once its remaining bytes arrive. Existing byte, collection,
string, binary, nesting, opaque-preservation, and terminal structural-failure behavior remains unchanged.

**Minimal repair.** Make the indefinite-string branch return `INCOMPLETE` whenever `scanStringChunk` neither
completes the outer item nor advances the scanner. Keep all state in the existing scanner and parser. Add
regressions for partial byte- and text-string chunk headers and bodies, nested containers, repeated fragments,
buffer growth, completion, truncation, malformed chunks, and splits at every byte boundary.

**Alternatives and consequences.** Rejecting indefinite strings would narrow the documented wire contract.
Adding timeouts or extra threads would only mask a deterministic parser loop. A new outer frame or parser
abstraction is unnecessary; the defect is a missing no-progress distinction in the current state machine.

**Confidence.** High. The loop follows directly from the two branches that return without changing
`position`; runtime verification has not yet been run.

### 4. Raw journal preservation duplicates payload storage and CBOR traversal

**Problem.** A decoded journal record owns separate copies of both `encodedPayload` and the complete
`encodedRecord`, even though the payload is already a byte range inside the record. Decoding first scans the
whole item, scans its array fields again, and parses selected fields; typed payload access copies and parses the
payload yet again. Server storage then copies the record and decodes it once more because the public record can
carry metadata inconsistent with its encoded bytes. Large PTY records therefore pay avoidable allocation and
parsing costs on live replication and persistence paths.

**Sources.** The repeated passes and copies are visible in
[`SessionEventCodec.decode`](src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java#L82),
[`CborArrayItems.addItem`](src/main/java/pro/deta/orion/agent/protocol/CborArrayItems.java#L48), and
[`SessionEventCodec.payload`](src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java#L144).
[`SessionEventRecord`](src/main/java/pro/deta/orion/agent/protocol/SessionEventRecord.java#L5) publicly stores
both byte values, and [`ProtocolBytes`](src/main/java/pro/deta/orion/agent/protocol/ProtocolBytes.java#L13)
defensively copies at its boundaries. Server append validation copies and decodes the complete record in
[`SessionJournal.validateRecords`](../agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java#L487).
Exact opaque preservation and maximum-payload behavior are covered by
[`SessionEventCodecTest`](src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java#L40) and its
[large-payload case](src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java#L123).

**Documented behavior.** The [protocol specification](protocol/README.md#L101) requires the encoded payload,
unknown event types, and future record tails to survive byte-for-byte. The
[stream-decoding design](../docs/plans/2026-09-03-typed-agent-protocol-stream-decoding-design.md#L137) also keeps
journal delivery byte-oriented; it does not require separate backing arrays or repeated parsing.

**Contract.** Preserve immutable public byte ownership, exact complete-record and opaque-payload bytes, EventId
and event-type metadata, future tails, unsigned EventId behavior, and the current separation between structural
and known-field limits. Storage must continue rejecting a caller-constructed record whose metadata disagrees
with its encoded bytes while that inconsistent public construction remains possible.

**Minimal repair.** First make a decoded record own one encoded-record backing array plus a private payload
range, while public byte access remains defensive. Only then consider deriving metadata from that canonical
representation so storage can avoid re-decoding, and consolidate scanner/array/reader passes where the same
range information can be reused. Each step should independently preserve all fixtures and limit behavior.

**Alternatives and consequences.** Keeping two public byte values preserves the simplest record shape but
retains peak-memory duplication. Removing storage validation before record consistency is guaranteed would
permit corrupt persisted records. A general CBOR DOM or new shared parsing framework would add a broader
abstraction than the current requirement justifies.

**Confidence.** High on duplicate ownership and traversal; no allocation or throughput benchmark has yet
measured their absolute production cost.

### 3. Typed `PTY_INPUT` assigns the native input UUID the wrong identity

**Problem.** The server command contains both a server `CommandId` and a distinct input UUID. AgentD sends the
UUID to the native host, and the native journal writes its textual UUID into `PTY_INPUT`. The Java journal model
and shared protocol table instead call that text a `CommandId`. A server command such as `command-1` with a
different input UUID is therefore journaled under the UUID, while the typed Java API reports that value as the
server command ID. The compatibility fixture hides the mismatch by wrapping UUID text in `CommandId`.

**Sources.** The two source identities are declared by
[`AgentMessage.Input`](src/main/java/pro/deta/orion/agent/protocol/AgentMessage.java#L210), while
[`SessionEventPayload.PtyInput`](src/main/java/pro/deta/orion/agent/protocol/SessionEventPayload.java#L14) and
[`SessionEventCodec.decodePtyInput`](src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java#L122)
label the journal field as `CommandId`. AgentD serializes the UUID in
[`NativeControlCodec`](../agentd/src/main/java/pro/deta/orion/agentd/session/NativeControlCodec.java#L20); the
host appends those 16 bytes in
[`apply_input`](../session-host/src/platform/unix.rs#L1190) and writes their textual form in
[`encode_event`](../session-host/src/journal.rs#L850). The shared fixture constructs the misleading wrapper in
[`AgentProtocolFixtureTest`](src/test/java/pro/deta/orion/agent/protocol/AgentProtocolFixtureTest.java#L41),
while the [native live-peer test](../agentd/src/test/java/pro/deta/orion/agentd/session/NativeControlLivePeerTest.java#L42)
exercises a real input UUID.

**Documented behavior.** The [shared protocol table](protocol/README.md#L90) currently calls the field a
`CommandId`. The [native protocol](../session-host/protocol/README.md#L93) says it preserves the input identity
and explicitly assigns replay protection to `operationSequence`, not to this field. No production caller of
`decodeKnownPayload` was found.

**Contract.** Preserve the version-1 journal's existing text bytes and byte-for-byte fixtures. Distinguish the
server command correlation ID, the input identity carried into `PTY_INPUT`, and the native operation sequence
used for admission/replay. This finding does not establish a new deduplication or journal-confirmation policy.

**Minimal repair.** Correct the shared documentation and typed Java payload name/meaning to input identity while
preserving the existing text wire representation and permissive version-1 decoding. Update fixtures and tests
to use different command and input identities so they can no longer mask the boundary.

**Alternatives and consequences.** Changing the persisted field to raw UUID bytes or adding an operation
sequence is a versioned wire change and is not needed to fix the semantic label. A new wrapper type would state
the domain more strongly but adds a public concept without a current production typed-payload consumer.
Documentation-only correction would leave the Java API actively misleading.

**Confidence.** High on the native producer path; medium on the best Java representation because the typed
payload API currently has no production consumer.

### 7. Unsupported handshake versions are discarded before negotiation policy sees them

**Problem.** `AgentProtocolCodec` rejects an unsupported `HELLO` or `WELCOME` version as a semantic error. The
sequence parser classifies every semantic decode error as recoverable, and AgentD's HTTP/2 transport logs and
discards recoverable control items. `AgentControlService` therefore never sees the unsupported `WELCOME` that
should fail negotiation. An unsupported `WELCOME` followed by a supported one can complete the handshake,
contrary to the documented negotiation failure policy.

**Sources.** Version rejection occurs in
[`AgentProtocolCodec.requireCurrent`](src/main/java/pro/deta/orion/agent/protocol/AgentProtocolCodec.java#L502)
and is converted to a recoverable outcome by
[`CborSequenceParser.decodeAvailable`](src/main/java/pro/deta/orion/agent/protocol/CborSequenceParser.java#L77).
[`JettyHttp2Transport.deliverControl`](../agentd/src/main/java/pro/deta/orion/agentd/transport/JettyHttp2Transport.java#L481)
only logs that outcome; [`AgentControlService.receiveControl`](../agentd/src/main/java/pro/deta/orion/agentd/core/AgentControlService.java#L121)
receives decoded messages only. [`AgentHandshake.accept`](../agentd/src/main/java/pro/deta/orion/agentd/core/AgentHandshake.java#L44)
does reject a typed unsupported `WELCOME`, and
[`AgentHandshakeTest`](../agentd/src/test/java/pro/deta/orion/agentd/core/AgentHandshakeTest.java#L50) covers that
unit path, but it bypasses production decoding and transport delivery.

**Documented behavior.** The [protocol specification](protocol/README.md#L32) says unsupported versions fail
negotiation and distinguishes that policy from recoverable semantic decoding. The handshake exchange is
defined at [the same specification](protocol/README.md#L68).

**Contract.** The generic sequence decoder may continue recovering from semantic failures, and version 1
remains the only supported wire version. During the initial control handshake, however, an unsupported peer
version must produce an application-level handshake failure without changing local sessions. Ordering must be
preserved so a later valid item cannot erase the earlier negotiation failure.

**Minimal repair.** Carry the existing `UNSUPPORTED_VERSION` rejection through the control transport boundary
to the handshake owner, using the existing ordered receive/failure flow rather than changing all semantic
decode failures into terminal structural errors. Add production-path tests for an unsupported `WELCOME` alone
and unsupported-then-supported messages across multiple chunkings.

**Alternatives and consequences.** A version-aware typed envelope could expose both decoded values and rejects
uniformly, but broadens the transport API. Making every semantic failure terminal would discard the deliberate
recovery contract. Allowing the next supported `WELCOME` would require changing the documented product policy,
not merely the implementation.

**Confidence.** High on the current loss path; medium on whether immediate handshake failure remains the desired
product behavior despite the current authoritative specification.

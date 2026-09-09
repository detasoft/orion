# Module Review: `agent-protocol`

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

**Priority signals.** Importance: medium — the duplication affects every decoded journal record on live
replication and persistence paths, but no production latency or allocation measurement establishes a blocking
impact. Repair ease: low — the smallest safe repair changes record ownership and storage validation across
modules while preserving exact bytes, defensive access, fixtures, and separate limit policies.

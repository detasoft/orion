# Module Review: `agent-protocol`

Date: 2026-09-03  
Status: reviewed in isolation

## Scope and coverage

This review covers the module's Maven definition, public Java model, control and journal codecs, incremental
CBOR Sequence decoders, private CBOR implementation, protocol specification, compatibility fixtures, tests,
and module-specific history. It deliberately does not inspect callers or implementations in other modules.

The review is static and read-only apart from this report. Maven verification was not run because repository
review rules assign verification to implementation work rather than to a documentation-only architecture
review.

## Current conceptual model

`agent-protocol` is a dependency-free shared wire-contract artifact with four responsibilities:

1. Model AgentD/server control messages as a sealed `AgentMessage` hierarchy.
2. Model session journal records while preserving unknown payloads and future fields byte-for-byte.
3. Encode and decode both contracts as CBOR items.
4. Split arbitrarily chunked CBOR Sequences into complete control messages or journal records.

The module deliberately separates message shape from connection policy. `AgentProtocolCodec` accepts messages
from both directions, unauthenticated legacy `HELLO`, and any message order. Direction, handshake ordering, and
the requirement to authenticate a server-facing connection remain obligations described in prose rather than
in the decoder.

The CBOR path has three representations of the same input. `CborItemScanner` finds boundaries,
`CborArrayItems` finds top-level fields, and `CborReader` builds values for selected fields. Opaque control
messages and journal records additionally retain encoded bytes for forwarding.

## Highest-value findings

### 1. A decoder error makes protocol behavior depend on transport chunk boundaries

**Finding.** A valid item immediately before an invalid item is delivered or silently consumed depending only
on whether the two items arrived in separate `accept` calls. That contradicts the documented rule that HTTP/2
DATA boundaries have no protocol meaning.

**Evidence.** `CborSequenceBuffer.accept` extracts every complete item from the supplied bytes and advances its
pending buffer before semantic decoding begins. `AgentProtocolDecoder.accept` and `SessionEventDecoder.accept`
then decode the returned list into a local list. If item N fails, the method throws, items 0 through N-1 are not
returned, and those bytes have already left the sequence buffer. A structural error found while scanning a
later item likewise prevents the valid prefix from being returned. Tests cover arbitrary chunking of valid
sequences and an invalid item by itself, but not a valid prefix followed by an invalid item in the same chunk.

**Why it likely exists.** Framing and semantic decoding were separated into convenient batch operations, but
the public all-items-or-exception return type cannot represent both a successfully decoded prefix and a later
terminal error.

**Simpler model.** Consume and decode one complete item at a time. Return an explicit decode result containing
the ordered valid prefix and an optional terminal failure, or deliver items to a caller callback before
reporting the failure. Once a terminal failure is observed, poison or close the decoder explicitly rather than
leaving recovery implicit.

**Contract change.** A call may expose valid messages preceding a malformed message and then report a terminal
error. Today the same prefix is exposed only when the transport happened to split it into an earlier call.

**Consequences.** Processing becomes invariant under arbitrary DATA/chunk boundaries, and callers receive an
unambiguous consumption contract. The current convenient `List<T>` plus checked exception API must change.

**Confidence.** High.

### 2. `SessionState` combines lifecycle with observation and journal health

**Finding.** One mutually exclusive enum represents several independent dimensions. `STARTING`, `RUNNING`,
`EXITED`, and `FAILED` describe execution lifecycle; `JOURNAL_GAP` describes replication availability;
`LOST` describes an observer's knowledge; and `DEGRADED` is an unspecified health summary.

**Evidence.** `AgentMessage.SessionState` contains all seven values. Both `SessionDescriptor` and
`AgentMessage.SessionOpen` allow exactly one of them while also carrying first/last available event IDs. The
specification allocates numeric values but defines neither transitions nor precedence. It cannot represent a
running or exited session that simultaneously has a journal gap.

**Why it likely exists.** A compact status field accumulated states needed by discovery, lifecycle, and journal
replication without first deciding which component owns each fact.

**Simpler model.** Keep one execution lifecycle (`STARTING`, `RUNNING`, `EXITED`, `FAILED`). Derive a journal
gap from the requested cursor and advertised available range. Represent loss as absence or an observation
result, and report concrete diagnostics instead of a generic `DEGRADED` lifecycle value.

**Contract change.** Future messages would no longer encode every condition as one state number. Existing v1
codes can remain readable during migration but should stop being emitted once their replacement is available.

**Consequences.** State combinations no longer require undocumented precedence, and the event range becomes
the source of truth for replication availability. A versioned migration is required if these states are
already persisted or exchanged.

**Confidence.** High that the axes are mixed; medium on compatibility because external use was out of scope.

### 3. Input retry identity is split between two identifiers and changes at the journal boundary

**Finding.** The protocol has no single identity that can prove a retried input became a journaled operation.
`INPUT` carries both `CommandId` and an input UUID, while the corresponding `PTY_INPUT` event retains only the
`CommandId`.

**Evidence.** `AgentMessage.Input` is `[CommandId, SessionId, inputId, bytes]`. `SessionEventPayload.PtyInput`
and the documented event allocation are `[CommandId, bytes]`. The specification calls `CommandId` a stable
logical identity but does not define the input UUID's scope or lifetime. `CommandOutcome.DUPLICATE` exists, but
the contract does not say which identifier establishes duplication or how it relates to a journal record.

**Why it likely exists.** Command/result correlation and idempotent input delivery were added as separate
concerns, then represented by adjacent identifiers instead of one explicit operation contract.

**Simpler model.** Separate opaque client correlation data from one per-session operation identity. For
retryable effects, carry a monotonic operation sequence unchanged into the resulting journal record and
acknowledge a contiguous high-watermark. If that guarantee is not required, choose either `CommandId` or the
input UUID as the sole idempotency key and delete the other.

**Contract change.** The control-message and journal-record fields change, and duplicate detection gains an
explicit scope and retention rule. Existing v1 journal records need transitional decoding if already durable.

**Consequences.** Retry, deduplication, journal confirmation, and cache eviction share one identity instead of
requiring an undocumented mapping. This gives up treating every command as a completely standalone request.

**Confidence.** High that the current mapping is incomplete; medium on which identifier should survive.

### 4. Raw preservation causes repeated parsing and copies of the same CBOR bytes

**Finding.** Forward compatibility is valuable, but its implementation currently requires several independent
CBOR traversals and duplicate byte ownership rather than one span-aware representation.

**Evidence.** A control item is scanned by `CborItemScanner`, split again by `CborArrayItems`, and its known
fields are copied into separate arrays and parsed by `CborReader`. `SessionEventCodec` follows the same path.
For every journal record it stores both a copied `encodedPayload` and a copied `encodedRecord`; known-payload
decoding copies those bytes again. The scanner, array splitter, and reader each implement their own container,
depth, and length handling.

The limit contract already differs among those paths. `CborItemScanner` applies item, collection, and nesting
limits but deliberately does not apply `maxStringBytes` or `maxBinaryBytes` to opaque fields. Tests require
unknown messages and known-message tails to bypass those field limits, while the protocol README broadly says
that decoders enforce configured string and binary limits.

**Why it likely exists.** A generic value reader was combined with a raw-byte forwarding requirement without
introducing byte spans as the shared internal representation.

**Simpler model.** Use one cursor that validates structure, exposes top-level and nested spans, and decodes only
the fields whose v1 semantics are known. Let a journal record own one encoded byte array plus payload offsets;
retain defensive copies only at the public boundary. Explicitly distinguish structural item limits from
semantic limits on known fields.

**Contract change.** No wire change is required. The documented treatment of oversized opaque strings and
byte strings must be made explicit; choosing to enforce limits on them would narrow forward compatibility.

**Consequences.** The module can remove at least one CBOR traversal/representation, reduce peak memory for
large PTY output records, and eliminate limit drift. A span-based internal API is less general than the current
full CBOR value tree, but the module does not expose that tree publicly.

**Confidence.** High.

### 5. Connection facts are repeated as independent snapshots with no consistency owner

**Finding.** The wire model repeats identity and mostly static facts in several messages, but this module does
not define whether or how they must agree during one connection.

**Evidence.** `HELLO` carries agent ID, instance ID, agent version, machine, and capabilities. `HEARTBEAT`
repeats both IDs. `AGENT_STATUS` repeats both IDs, version, machine, and capabilities, and adds an
`activeSessions` count even though `SESSION_LIST` carries the sessions themselves. The item codec is stateless,
so it accepts contradictory snapshots. Tests verify each shape independently, not cross-message invariants.

**Why it likely exists.** Self-contained messages are easy to route across independent logical streams, while
the protocol description also implies a connection established by `HELLO`/`WELCOME`. The intended routing
unit is not defined inside this module.

**Simpler model.** If messages belong to one authenticated connection, bind immutable agent identity and
capabilities at `HELLO`; make heartbeat carry only liveness data, make status carry only mutable metrics, and
derive the active count from the session inventory. If messages must be independently routable, state that
requirement and make snapshot consistency explicitly best-effort rather than implied.

**Contract change.** Removing repeated fields requires a later protocol version. Enforcing consistency in v1
would cause previously accepted contradictory sequences to fail.

**Consequences.** Connection-scoped identity removes several sources of disagreement and smaller messages, but
loses self-contained routing. This recommendation depends on transport ownership that was intentionally not
inspected outside the module.

**Confidence.** Medium.

## Smaller contract inconsistencies

- `AgentProtocolLimits` still publishes `DEFAULT_MAX_FRAME_BYTES`, `withMaxFrameBytes`, and `maxFrameBytes`,
  even though the specification explicitly says the protocol has no frame header or protocol-level frames.
  These aliases have no use inside the module and preserve obsolete vocabulary.
- The README calls map encoding canonical but defines Java `String` order rather than a language-neutral CBOR
  deterministic-order rule. Unless encoded map bytes are signed or hashed, map order is not a useful wire
  guarantee and should not be frozen as protocol semantics.
- `HELLO`/`WELCOME` are described as negotiating versions, but the messages carry one version each and
  `AgentProtocolCodec.requireCurrent` rejects every value except the local `CURRENT` constants. The implemented
  behavior is a two-field compatibility assertion, not multi-version selection.
- `AgentMessageType.Direction` is declarative metadata only. The decoder accepts either direction, and the
  generic `HELLO` model permits absent authentication while prose delegates rejection to an endpoint. That may
  be an intentional codec/policy split, but the public package description currently calls the whole artifact
  a shared contract without naming this boundary.
- Strong wrapper types exist for most identities, but the input UUID remains a raw `UUID`. This makes its role
  look incidental despite being the only input-specific identity.

## Things to try deleting

- The all-items-or-exception batch contract in both incremental decoders.
- `DEGRADED`, `JOURNAL_GAP`, and `LOST` as lifecycle states once their facts are derived or represented at the
  correct boundary.
- One of the two input identities, unless client correlation and operation identity are explicitly separated.
- The copied `encodedPayload` stored beside the complete encoded record; retain payload offsets instead.
- One of the overlapping CBOR structural passes and the generic value types not needed for known fields.
- Repeated agent identity/static fields and the derived active-session count if the connection owns those
  facts.
- The three legacy `frame` limit aliases.
- The Java-specific canonical map-order guarantee if no byte-level signature or hash depends on it.

## Proposed conceptual model

- One v1 item codec with one span-aware CBOR structural parser.
- One incremental sequence contract whose observable results do not depend on transport chunk boundaries.
- One authenticated connection identity established at `HELLO`, if control messages are connection-scoped.
- One session execution lifecycle; journal availability is derived from event ranges and sync cursors.
- One operation identity for retryable effects, carried unchanged from command acceptance to journal record;
  client correlation fields remain opaque and separate.
- Unknown messages, payloads, and future tails remain raw bytes and do not require semantic understanding.
- Structural resource limits and known-field validation are named and enforced as different policies.

## Incremental migration path

1. Add regression cases for `valid item + invalid item` in one chunk and in two chunks for both incremental
   decoders. Define one identical externally visible result for both chunkings.
2. Change incremental decoding to expose a valid prefix and terminal failure explicitly, then poison the
   decoder after that failure.
3. Introduce internal byte spans and make `SessionEventRecord` own one encoded record. Preserve all current
   fixtures and byte-for-byte opaque forwarding while collapsing the redundant CBOR passes.
4. Clarify structural versus known-field limits and remove the obsolete frame aliases.
5. Define command correlation, operation deduplication, and journal confirmation as separate concepts. Add a
   versioned operation identity before changing the persisted `PTY_INPUT` shape.
6. Stop producing mixed-axis session states after readers can derive journal gaps and carry execution lifecycle
   separately.
7. Decide whether control messages are connection-scoped. Only then remove repeated snapshots in a later
   protocol version or document why self-contained routing is required.
8. Inventory concrete capability, configuration, and metric keys before treating the generic string maps as a
   stable extension protocol.

Each wire change should keep the checked-in v1 fixtures readable and introduce new fixtures for the new
version rather than silently changing existing bytes.

## Do not change

- Preserve byte-for-byte forwarding of unknown control items and complete journal records with unknown payloads
  or future tails. This is a verified compatibility boundary.
- Preserve strict UTF-8 decoding, duplicate map-key rejection, bounded nesting/collection sizes, and the hard
  per-item byte cap.
- Preserve full unsigned 64-bit `EventId` ordering and conversion; Java's signed `long` representation is
  deliberately hidden by the value type.
- Preserve separate Agent protocol and journal format version fields. The two contracts can evolve
  independently even though only version 1 is currently accepted.
- Preserve defensive byte ownership at public API boundaries and credential length validation.
- Preserve definite-length output and permissive reading of valid indefinite-length CBOR containers unless a
  cross-language compatibility decision explicitly narrows the accepted format.

## Open questions

- Are valid messages before a malformed item expected to take effect before the connection is closed?
- Is one authenticated HTTP/2 connection the owner of agent identity, or must every logical stream be routable
  without connection context?
- Which identifier currently owns command deduplication, and for how long must that decision survive?
- Have v1 journal records already been persisted such that changing `PTY_INPUT` requires permanent legacy
  decoding?
- Can a session be running or exited while its journal has a gap, and which condition should a user see?
- Are capability/configuration map bytes signed or hashed, or is their current ordering only for deterministic
  fixtures?
- Which capability, configuration, and metric keys are part of the actual v1 contract?

# Session Event Record Byte Ownership Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve exact immutable session-event bytes while allocating only one encoded-record backing array
per decoded record and avoiding a defensive encoded-payload copy before typed decoding.

**Architecture:** Keep the public `SessionEventRecord` shape and storage validation contract unchanged. Make
`ProtocolBytes` an immutable view over a private array range, copy the complete record once in
`SessionEventCodec.decode`, and derive the payload view from that owned record. Let the package-private CBOR
reader consume a `ProtocolBytes` range directly; no new public API, wire field, persisted state, or compatibility
path is introduced.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Maven.

---

## Required delta and constraints

Current model: `SessionEventCodec.decode` separately copies `encodedPayload` and `encodedRecord` even though
the payload is a range of the record. `decodeKnownPayload` then calls `toByteArray()` before parsing that range.

Required behavioral delta: a decoded event retains one owned full-record byte array; its payload value is an
immutable view over the same backing bytes, and typed payload decoding does not first clone the encoded payload.

Preserve:

- the public `SessionEventRecord` components and constructor;
- defensive ownership at every public `ProtocolBytes` boundary;
- byte-exact complete records, opaque payloads, and future tails;
- current equality, hash, size, and redacted string behavior for `ProtocolBytes`;
- unsigned `EventId`, event type, nesting, collection, string, binary, and message limits;
- rejection by `SessionJournal` of malformed or caller-forged records under the storage's own limits.

Non-goals: changing journal append APIs, trusting records merely because another codec produced them, replacing
the CBOR scanner/reader model, adding a general buffer abstraction, or changing wire/persisted bytes.

## Considered approaches

1. **Range-aware `ProtocolBytes` (selected).** It removes the large duplicate allocation locally while retaining
   every public and storage contract. A small private offset pair is sufficient.
2. **Replace the public record with a canonical class.** This could make inconsistent construction impossible,
   but changes a public Java contract and still does not prove that the record was decoded under storage's own
   configured limits.
3. **Skip storage decoding for codec-produced values.** Provenance and limit compatibility would require new
   trusted state or another append path; trusting all values would allow malformed persisted records.

The remaining scanner passes have distinct jobs: sequence framing, structural/string-limit validation, and
field-range discovery. They are outside this repair unless runtime evidence establishes a further bottleneck.

### Task 1: Specify single-backing ownership with allocation coverage

**Files:**

- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java`

1. Add a focused test that prepares a large valid event before measurement, decodes it, and uses the JVM's
   per-thread allocation counter to prove decoding no longer allocates a second payload-sized backing array.
2. Assert the decoded full record and payload remain byte-exact and independently defensive through their public
   accessors.
3. Run
   `make run-test MODULE=agent-protocol TEST='SessionEventCodecTest#retainsLargeEventWithOneOwnedBacking'`.
   Before implementation, expect the allocation assertion to fail because both record and payload are copied.

### Task 2: Add immutable byte ranges and derive the payload view

**Files:**

- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/ProtocolBytes.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java`

1. Store a private backing array plus validated `from`/`to` offsets in `ProtocolBytes`.
2. Keep public `copyOf` and `toByteArray` defensive. Make `size`, equality, hash, and `toString` range-aware.
3. Add only the package-private slice/access needed by the codec and reader; do not expose mutable bytes publicly.
4. In `SessionEventCodec.decode`, copy the complete validated item once and derive `encodedPayload` from its
   relative range.
5. Re-run the focused test and expect it to pass.

### Task 3: Remove the typed payload's defensive pre-parse copy

**Files:**

- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborReader.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java`

1. Add a `CborReader` entry point for the immutable internal `ProtocolBytes` range.
2. Decode known payloads from that range without calling `toByteArray()` first.
3. Add allocation coverage for a large known PTY payload and keep the existing typed-value assertions.
4. Verify the new test fails on the old copy path, then passes with the range-aware reader.

### Task 4: Verify contracts and architectural scope

**Files:**

- No additional production files expected.

1. Run focused protocol tests with
   `make run-test MODULE=agent-protocol TEST='SessionEventCodecTest,SessionEventDecoderTest,AgentProtocolFixtureTest'`.
2. Run `mvn verify -Pdev -T 4` from the repository root.
3. Review the final diff for public API, wire, storage, limit, and active session-replication compatibility.
4. Confirm no second byte-owner concept, compatibility adapter, storage bypass, or unrelated CBOR refactor was
   introduced.
5. Commit one logical implementation change after the required review preparation.

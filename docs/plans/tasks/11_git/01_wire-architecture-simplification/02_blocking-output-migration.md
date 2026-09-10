# Complete Blocking Git Output Migration

Status: todo

Remove the output objects that still model resumable serialization even though
blocking `BufferedByteOutput` writes now complete synchronously.

## Scope

- Delete `OutputSerialization`, `AsciiPacketSequenceSerialization`,
  `PacketListSerialization`, and `PktLineSerialization`.
- Replace their call sites with direct, ordinary writes without creating
  intermediate `List<String>` or `List<byte[]>` solely for serialization.
- Preserve the existing byte-for-byte tests for advertisements, acknowledgments,
  status reports, shallow information, and protocol v2 sections.
- Replace `LegacySideBandResponse`, `LegacyPackResponse`, and
  `ProtocolV2PackfileResponse` plus `advance()` with one-shot `send...` methods.
- Make each `send...` method close its `NativePackProducer` on success and on
  every write, flush, or runtime failure.
- Preserve chunked pack streaming and blocking backpressure.

## Non-Goals

- Do not broaden this slice into removal of other wire helpers or error types.
- Preserve programmatic wire-error classification because it remains useful
  for server logs and diagnostics even when clients do not consume it.

## Follow-Up Review

After implementation, audit the output path without modifying it and record
any further safe removals as separate tasks; do not fold them into this migration.

## Detailed implementation plan

### Task 2: Replace resumable serializers with direct writes

**Files:**

- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransportTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/AsciiPacketUtils.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/OutputSerialization.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/AsciiPacketSequenceSerialization.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/PacketListSerialization.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/PktLineSerialization.java`

1. Extend existing byte-for-byte tests for v1 advertisement, v2 capability
   advertisement, `ls-refs`, acknowledgments, shallow information, and receive
   status so each touched output family has a stable expected wire sequence.
2. Run `GitBlockingWireTransportTest` and record GREEN as the characterization
   baseline.
3. Add a direct helper that validates one payload and writes its header,
   optional sideband byte, and payload immediately:

   ```java
   private void writeAsciiPacket(String payload, boolean sideband)
           throws IOException {
       validateAsciiPacket(payload, sideband ? 1 : 0);
       byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
       if (sideband) {
           outputSink.write(pktLineWriter.writeSidebandHeader(
                   SideBandChannel.DATA.wireValue(), bytes.length));
       } else {
           outputSink.write(pktLineWriter.writeDataHeader(bytes.length));
       }
       outputSink.write(bytes);
   }
   ```

4. Replace serializer construction with ordinary loops that call the direct
   helper and write the final control packet once. Validate all domain inputs
   before the first output byte where the current API promises validation
   before delivery.
5. Do not build `List<String>` or `List<byte[]>` merely to pass encoded output
   to another layer; domain collections supplied by callers may still be
   iterated directly.
6. Write receive-pack's nested pkt-lines directly, including the outer
   sideband header when enabled, and flush at the same response boundary.
7. Delete the four serializer types and remove `sendSerialization` plus unused
   imports/helpers. Do not remove unrelated pkt-line helpers in this slice.
8. Run:

   ```bash
   mvn test -Pdev -T 4 -q -pl git/git-parser -am \
     -Dtest=GitBlockingWireTransportTest \
     -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected: GREEN with unchanged byte assertions.
9. Commit the direct serializer replacement with its characterization tests.

### Task 3: Replace stateful response wrappers with one-shot sends

**Files:**

- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/ProtocolV2PackfileResponseTest.java`
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/ProtocolV2ShallowInfoResponseTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java`

1. Convert response tests to call one-shot `sendLegacySideBand64k`,
   `sendLegacyPack`, and `sendProtocolV2Packfile` methods while preserving all
   existing byte-for-byte expectations.
2. Add or retain tests proving producer closure after success, `IOException`,
   and runtime delivery failure.
3. Run the focused parser and transport tests and record RED for the missing
   one-shot methods.
4. Replace each `begin...` method, inner `*Response` type, and `advance()` with a
   direct send method. Own the producer at method entry:

   ```java
   public void sendLegacyPack(
           NativePackProducer producer,
           boolean sendNakBeforePack) throws IOException {
       try (NativePackProducer owned = Objects.requireNonNull(
               producer, "producer")) {
           // Write NAK when required, stream the producer, and flush.
       }
   }
   ```

5. Stream protocol v2 pre-pack sections directly in order rather than storing
   an encoded packet list on a response object.
6. Remove session-side nullable response variables and `finally` blocks; the
   invoked `send...` method is the sole producer owner.
7. Rerun the focused tests and expect GREEN.
8. Audit the resulting output path.
   Record additional safe removals as separate task nodes, or record that none
   were found. Do not remove further helpers or the `GitWireError` classification
   in this migration; server diagnostics still use structured error kinds.

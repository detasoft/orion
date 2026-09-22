# Module Review: `git/git-client`

## 3. Direct and sideband report-status decoding apply different validation

**Problem and evidence.**
[readDirectStatus](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L344)
collects an unbounded list and uses strict UTF-8/control-byte validation.
[readSideBandStatus](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L364) limits accumulated
bytes to 1 MiB,
then [parsePacketLines](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L414) decodes with
replacement-tolerant
`new String(..., UTF_8)`. An invalid `ng` explanation can fail directly but become a normal rejection
through sideband. A direct peer can send arbitrarily many packets before expected-ref validation occurs.

**Contract.** Wire framing differs; logical text validation and aggregate status bounds have no documented
reason to differ. Preserve expected-ref completeness, rejection results, progress channels, malformed-packet
classification and nested trailing-data rejection. The path serves bootstrap push and synchronization.

**Minimal repair and validation.** Share bounded logical-status validation/collection inside the existing
wire class, retaining separate direct framing and sideband demultiplexing.
Extend the existing direct/sideband parameterized
[GitBlockingClientsTest](src/test/java/pro/deta/orion/git/client/GitBlockingClientsTest.java) cases with
malformed UTF-8, control
bytes and oversized input. Retain Unicode success and malformed nested-frame coverage.

**Alternatives and consequences.** A public parser abstraction is unnecessary; replacing both paths with
permissive decoding would weaken the existing direct contract. No persisted representation changes.

**Confidence and priority.** High from both live parsing paths; no reproduction executed.
P2 with externally controlled allocation and interpretation; medium local repair.

## 4. Packet parsing recreates ByteBuf ownership around packet-owned arrays

**Problem and evidence.**
[GitPktLine.Data](../git-parser/src/main/java/pro/deta/orion/git/parser/v2/pkt/GitPktLine.java#L84) owns
`byte[]`.
[payloadBuffer](../git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java#L56)
only wraps it in `Unpooled.wrappedBuffer`.
All five production callers are in
[GitBlockingClientWire](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L42);
each adds a release/finally scope. Sideband status additionally copies a slice to another array before
appending it. No extra lifetime, isolation or streaming guarantee is supplied by these wrappers.

**Contract.** Preserve binary payloads, strict text validation, size limits, progress callbacks and
caller-owned output lifetime. Advertisement text permits NUL; blindly using `Data.text()` would reject it.

**Minimal repair and validation.** Read `Data.content()` directly, remove `payloadBuffer` and its local
release scopes, and use array offset/length operations for sideband bytes. Existing BufferedByteOutput
already supplies the bridge needed by output implementations. Retain raw-pack ByteBuf buffering and
`readRawInto`. Preserve fragmented input, raw/sideband packs, Unicode, malformed packets, size limits,
callbacks in [GitBlockingClientsTest](src/test/java/pro/deta/orion/git/client/GitBlockingClientsTest.java), and
the parser's
[packet/raw switching test](../git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransportTest.java).

**Alternatives and consequences.** Changing every buffered I/O implementation would exceed this need.
This removes structural overhead, not a demonstrated leak, and requires only one small parser helper update.

**Confidence and priority.** High from repository-wide consumers. P3, easy local simplification;
independent of the larger transport lifecycle contracts.

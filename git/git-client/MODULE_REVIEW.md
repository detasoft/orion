# Module Review: `git/git-client`

## 4. Packet parsing recreates ByteBuf ownership around packet-owned arrays

**Problem and evidence.**
[GitPktLine.Data](../git-parser/src/main/java/pro/deta/orion/git/parser/v2/pkt/GitPktLine.java#L84) owns
`byte[]`.
[payloadBuffer](../git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java#L56)
only wraps it in `Unpooled.wrappedBuffer`.
All five production callers are in
[GitBlockingClientWire](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L42);
each adds a release/finally scope. Sideband status additionally copies a slice to another array before
appending it, and nested status text wraps the packet array again for validation.
No extra lifetime, isolation or streaming guarantee is supplied by these wrappers.

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

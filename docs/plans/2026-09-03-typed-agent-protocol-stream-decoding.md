# Typed Agent Protocol Stream Decoding Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Consolidate incremental CBOR Sequence parsing in `agent-protocol`, produce typed inbound messages, skip and log structurally bounded semantic failures, and disconnect only when a trustworthy next boundary cannot be found.

**Architecture:** A package-private generic sequence engine owns one reusable bounded input buffer and invokes a typed item decoder as soon as it finds a complete CBOR span. Public Agent and journal decoders return decoded values, recoverable item failures, and an optional terminal structural failure without losing an accepted prefix. Jetty consumes those results before releasing its borrowed `ByteBuffer`, logs recoverable failures, and closes only after ordered delivery of any valid prefix when structural synchronization is lost.

**Tech Stack:** Java 21, JDK `ByteBuffer`, Jetty HTTP/2, existing CBOR codecs, JUnit 5, AssertJ

---

### Task 1: Define the typed incremental result contract

**Files:**
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SequenceDecodeResult.java`
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SequenceDecodeIssue.java`
- Create: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolDecoderTest.java`
- Create: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventDecoderTest.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/package-info.java`

**Step 1: Write the chunk-invariant result tests**

Specify a result that can expose, in one call:

- one wire-ordered list whose entries are either decoded typed values or
  recoverable item issues with reason and encoded length;
- an optional terminal structural issue after the decoded prefix.

For both public decoders, test one chunk and several chunk divisions of the
same valid sequence and require identical decoded values. Add source-buffer
mutation tests proving returned values do not depend on the borrowed
`ByteBuffer` after `accept` returns.

**Step 2: Write recovery and terminal tests**

For both control and journal decoders, add these sequences:

```text
valid item + structurally complete semantic failure + valid item
valid item + structurally malformed item + otherwise valid bytes
```

The first returns both valid typed values plus one recoverable issue and leaves
the decoder usable. The second returns the valid prefix plus a terminal issue,
does not reinterpret later bytes, and rejects further `accept` calls until
reset. Verify that an incomplete tail waits for more data and becomes a
terminal truncation only when `finish` is called.

**Step 3: Run the focused tests and confirm the contract is absent**

Run outside the sandbox:

```shell
mvn test -Pdev -T 4 -q -pl agent-protocol -am \
  -Dtest=AgentProtocolDecoderTest,SessionEventDecoderTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the result types and `ByteBuffer` API do not exist.

**Step 4: Add the minimal immutable result values**

Implement an immutable result with a nested sealed typed outcome so successful
values and recoverable issues retain wire order. A recoverable issue contains
an `AgentProtocolException` and the complete encoded item length, not the raw
item. A terminal issue describes pending bytes and the structural failure.
Document at package level that callers must handle every issue and that a
terminal result poisons the decoder until reset.

**Step 5: Commit the contract**

Commit with:

```text
Define typed sequence decode results
```

### Task 2: Build one bounded generic sequence engine

**Files:**
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborSequenceParser.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborItemScanner.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolDecoder.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventDecoder.java`
- Delete: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborSequenceBuffer.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolDecoderTest.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventDecoderTest.java`

**Step 1: Add bounded-buffer and structural tests**

Cover definite and indefinite arrays, maps, byte/text strings, nested values,
reserved additional information, unmatched breaks, indefinite map keys without
values, invalid indefinite string chunks, the nesting limit, and a current
item that reaches `maxMessageBytes` without a complete boundary. Also prove
that many coalesced valid items may exceed the per-item limit in aggregate
without growing the retained buffer past one item.

**Step 2: Implement the generic engine**

Move the useful incremental scan state into `agent-protocol`.
`CborItemScanner` is the single owner of scan position and container state;
`CborSequenceParser` owns buffering and invokes that scanner. The engine must:

- accept `ByteBuffer` without introducing Netty;
- copy borrowed bytes into one reusable owned buffer;
- maintain bounded start/end buffer positions while the scanner maintains its
  scan position and container state;
- decode each complete item before advancing to the next one;
- compact only the incomplete tail and grow no further than
  `maxMessageBytes`;
- convert typed-decoder exceptions into recoverable issues after the boundary
  is known;
- return structural scanner failures alongside any already decoded prefix and
  poison itself until reset.

Do not search forward for a plausible CBOR initial byte after structural
failure.

**Step 3: Make both public decoders thin typed facades**

Construct the common engine with `AgentProtocolCodec` or `SessionEventCodec` as
the item decoder. Replace `accept(byte[])` with `accept(ByteBuffer)`, and expose
`finish`, `reset`, and `pendingBytes` consistently. Do not retain a second
incremental buffer implementation.

**Step 4: Run the focused tests**

Run the command from Task 1 and confirm all decoder tests pass.

**Step 5: Commit the unified sequence engine**

Commit with:

```text
Unify typed CBOR sequence parsing
```

### Task 3: Decode buffer ranges without transient item arrays

**Files:**
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborItemScanner.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborArrayItems.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborReader.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolCodec.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolCodecTest.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java`

**Step 1: Add range-decoding regression tests**

Decode an item from a non-zero range surrounded by unrelated bytes. Require
known values, an unknown control item, an unknown journal payload, and a future
journal tail to match the existing whole-array behavior byte-for-byte. Retain
strict UTF-8, duplicate map-key, nesting, collection, string, and binary limit
tests.

**Step 2: Run the codec tests and confirm range decoding is absent**

Run outside the sandbox:

```shell
mvn test -Pdev -T 4 -q -pl agent-protocol -am \
  -Dtest=AgentProtocolCodecTest,SessionEventCodecTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the codecs cannot decode a bounded range.

**Step 3: Add package-private range-aware codec paths**

Let the scanner, top-level array splitter, and reader operate on a bounded
range of the sequence engine's owned array. One-shot validation must invoke
the same structural scanner used by the incremental engine. Keep public
whole-array codec methods as delegating convenience APIs. Remove
`Arrays.copyOfRange` calls used only to pass complete items or top-level fields
between internal parsers.

Every returned value must still own any bytes it retains after the decoder
reuses its input buffer. Keep exactly the copies required by `ProtocolBytes`,
`AgentMessage.Unknown`, and `SessionEventRecord`; do not weaken defensive
ownership or change v1 encoding.

**Step 4: Run all Agent protocol tests**

Run outside the sandbox:

```shell
mvn test -Pdev -T 4 -q -pl agent-protocol -am
```

Expected: PASS, including compatibility fixtures.

**Step 5: Commit the range-aware codecs**

Commit with:

```text
Decode protocol items from bounded ranges
```

### Task 4: Replace AgentD raw inbound parsing with typed messages

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/transport/AgentTransport.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/transport/JettyHttp2Transport.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentControlService.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/transport/package-info.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/transport/CborSequenceDecoder.java`
- Delete: `agentd/src/test/java/pro/deta/orion/agentd/transport/CborSequenceDecoderTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlServiceTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentAssemblyTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/transport/JettyHttp2TransportTest.java`

**Step 1: Change transport tests to require typed callbacks**

Replace `onControlCbor` and `onSessionCbor` expectations with callbacks that
receive `AgentMessage`; the session response callback must also include its
`SessionId`. Use a typed `WELCOME` in the control service fake instead of
encoding and decoding a raw reply.

Add a transport test that feeds a complete semantically invalid item between
two valid messages. Require both valid typed messages, no disconnect/reset
signal, and continued use of the same stream. The protocol decoder tests must
also expose exactly one recoverable issue for this input, and the transport
implementation must log each such issue exactly once.

**Step 2: Run the focused AgentD tests and confirm they fail**

Run outside the sandbox:

```shell
mvn test -Pdev -T 4 -q -pl agentd -am \
  -Dtest=JettyHttp2TransportTest,AgentControlServiceTest,AgentAssemblyTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `AgentTransport` still exposes raw inbound CBOR.

**Step 3: Migrate each inbound stream to `AgentProtocolDecoder`**

Give the connection generation and every logical session response stream its
own decoder. Invoke `accept` synchronously while the Jetty `Stream.Data` buffer
is borrowed. Queue owned `AgentMessage` values to the existing callback
executor. Change `AgentControlService` to consume those values directly while
retaining `AgentProtocolCodec` only for outbound encoding.

For every recoverable issue, write an error log containing control/session
context, optional session ID, exception reason, and item length. Do not log raw
CBOR. Continue reading the same stream after the issue. Use the JDK logging API
already available to the standalone process rather than adding a new buffer or
transport dependency solely for diagnostics.

**Step 4: Preserve prefix-before-terminal ordering**

Queue each decode result as one ordered unit. Deliver its valid prefix before
calling connection failure or resetting a session stream for its terminal
issue. Stop demanding additional DATA once a terminal result is accepted, but
do not let generation invalidation retroactively discard that prefix. Continue
to discard callbacks only when an independently replaced generation makes them
stale.

**Step 5: Delete the AgentD decoder and its duplicate tests**

Delete both required files rather than leaving forwarding wrappers:

```text
agentd/src/main/java/pro/deta/orion/agentd/transport/CborSequenceDecoder.java
agentd/src/test/java/pro/deta/orion/agentd/transport/CborSequenceDecoderTest.java
```

The structural cases formerly tested there must exist in the new
`agent-protocol` decoder tests.

**Step 6: Re-run the focused AgentD tests**

Run the command from Step 2 and confirm it passes.

**Step 7: Commit the AgentD migration**

Commit with:

```text
Consume typed protocol messages in AgentD
```

### Task 5: Verify recovery over a live HTTP/2 peer

**Files:**
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/transport/JettyHttp2LivePeerTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlLivePeerTest.java`
- Modify: `agent-protocol/protocol/README.md`

**Step 1: Replace arbitrary CBOR fixtures with protocol messages**

Encode real `WELCOME`, command, and `SESSION_SYNC` messages for inbound test
DATA. Retain fragmented and coalesced delivery cases, but assert typed message
equality instead of raw byte-array equality.

**Step 2: Add live semantic-recovery cases**

On both a control response and a session response, send:

```text
valid message + structurally complete invalid known message + valid message
```

Send the same logical bytes with different DATA splits. Require the same valid
messages and error count, no transport disconnect for the semantic rejection,
and successful subsequent traffic on that stream.

**Step 3: Add live structural-terminal cases**

Send a valid message followed by a top-level unmatched break or another item
whose boundary cannot be established. Require delivery of the valid prefix
before failure. The control case disconnects the generation; the session case
resets only that session stream and leaves the control stream and another
session usable.

**Step 4: Document sequence error semantics**

Update the protocol README to state that DATA boundaries have no meaning,
complete semantic failures are skipped with diagnostics, structural loss is
terminal for the affected stream, and no heuristic resynchronization or outer
length marker exists. Preserve the existing unsupported-version negotiation
policy as a higher-level concern.

**Step 5: Run focused live-peer tests**

Run outside the sandbox:

```shell
mvn test -Pdev -T 4 -q -pl agentd -am \
  -Dtest=JettyHttp2LivePeerTest,AgentControlLivePeerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 6: Commit live recovery and documentation**

Commit with:

```text
Verify resilient protocol stream decoding
```

### Task 6: Verify the complete change

**Files:**
- Verify: `agent-protocol/**`
- Verify: `agentd/**`
- Verify: `docs/plans/current-work/agentd/typed-protocol-stream-decoding/TASK.md`

**Step 1: Prove duplicate parsing is gone**

Run:

```shell
test ! -e agentd/src/main/java/pro/deta/orion/agentd/transport/CborSequenceDecoder.java
test ! -e agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborSequenceBuffer.java
rg -n 'class CborSequenceDecoder|class CborSequenceBuffer|onControlCbor|onSessionCbor' agent-protocol agentd
```

Expected: both `test` commands pass and `rg` finds no obsolete class or raw
inbound callback. Inspect the remaining parser classes to confirm there is one
incremental structural implementation in `agent-protocol`.

**Step 2: Run module and reactor verification**

Run outside the sandbox:

```shell
mvn test -Pdev -T 4 -q -pl agent-protocol,agentd -am
mvn verify -Pdev -T 4
```

Expected: PASS.

**Step 3: Inspect the final branch**

Run:

```shell
git diff --check
git status --short
git log --oneline --decorate -10
```

Confirm that no protocol v1 bytes changed, no `ByteBuf` dependency was added,
raw opaque values remain byte-for-byte stable, and every class-level `@AiRule`
touched by the implementation still holds.

**Step 4: Prepare review and integration**

Request code review using `docs/reviews/RULES.md`. After fixes and verification,
squash the task branch, delete this completed leaf task and its parent link in
the squashed commit, cherry-pick it to `main`, run the required post-commit
`make test`, and remove the worktree and task branch.

# Git Minimal Wire Inbound Framing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the common inbound pkt-line framing loop that accepts fragmented
`ByteBuf` streams from real Git clients and carries the current
`ProtocolStage` from the control header into streaming payload processing.

**Architecture:** This work covers only the client-to-server framing part of a
native `git://` conversation. `ControlHeaderContinuation` reads the four-byte
pkt-line header and creates the payload Continuation for the current
`ProtocolStage`. Each stage-specific payload Continuation consumes exactly the
declared payload bytes. Protocol semantics, outbound responses, and raw PACK
ingestion are separate layers.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion `ContinuationRuntime`, JUnit 5,
AssertJ, Maven

---

## Current exchange slice

The current work is the shared inbound prefix of every supported conversation:

```text
real Git client
    |
    | fragmented TCP ByteBuf chunks
    v
ControlHeaderContinuation(stage)
    |
    | DATA(payloadLength)
    v
InitialRequestPayloadContinuation(payloadLength)
    |
    | bounded ByteBuf fragments
    v
stage-specific parsing (designed next)
```

The intended complete server will eventually support the default flows for:

- `git-upload-pack` protocol v0/v1 clone and fetch;
- `git-upload-pack` protocol v2 `ls-refs` and fetch;
- `git-receive-pack` protocol v0/v1 push.

This plan does not implement those conversations. It establishes the common
framing loop on which their Continuations will be built.

## Agreed design

### ByteBuf is the only inbound data carrier

Do not introduce `GitPacket`, a packet event hierarchy, or another object that
copies or wraps payload data. Every Continuation receives the caller-owned
network `ByteBuf` through `process(ByteBuf)`.

Pkt-line metadata such as `ControlState`, `ProtocolStage`, and the remaining
payload length may be stored as ordinary Continuation fields.

### ProtocolStage is an enum

Use the existing enum:

```java
public enum ProtocolStage {
    INITIAL_REQUEST,
    UPLOAD_V0_WANTS,
    UPLOAD_V0_HAVES,
    UPLOAD_V2_COMMAND,
    UPLOAD_V2_ARGUMENTS,
    RECEIVE_COMMANDS
}
```

`GitMinimalWireMachine` starts at `ProtocolStage.INITIAL_REQUEST`.
`ControlHeaderContinuation` uses the stage to select the DATA payload
Continuation directly. The initial stage creates
`InitialRequestPayloadContinuation`.

Do not add a `PacketRoute` strategy or a mutable global `currentStage` to
`GitMinimalWireMachine.Context`.

### ControlHeaderContinuation owns header fragmentation

`ControlHeaderContinuation` must consume exactly four header bytes. If a header
is split across input chunks, it retains only the one to three partial header
bytes needed for the next `accept`.

After decoding:

- DATA transitions directly to the payload Continuation selected by
  `ProtocolStage`; `INITIAL_REQUEST` creates
  `InitialRequestPayloadContinuation(context, payloadLength)`;
- FLUSH, DELIMITER, and RESPONSE_END will later transition according to the
  same `ProtocolStage`, without entering a payload Continuation;
- invalid hexadecimal headers, reserved length `0003`, and oversized pkt-lines
  transition to a typed completed error.

The current implementation's plain `await()` when fewer than four bytes are
available is not sufficient because the Netty adapter releases the caller-owned
input after `accept`.

### Payload Continuations are stage-specific

There is no generic concrete `PayloadContinuation`. The control header already
knows both `ProtocolStage` and `payloadLength`, so it creates the corresponding
stage-specific Continuation directly. The first implementation is
`InitialRequestPayloadContinuation`.

The stage-specific Continuation will own both the bounded payload read and its
incremental semantic state. It must not retain caller-owned input or read past
the declared payload boundary.

```java
private final GitMinimalWireMachine.Context context;
private int remainingBytes;
```

For every input:

```java
int length = Math.min(remainingBytes, input.readableBytes());
boolean last = length == remainingBytes;
ByteBuf fragment = input.readSlice(length);
remainingBytes -= length;
```

`InitialRequestPayloadContinuation` consumes `fragment` synchronously. It may
retain semantic state such as the partially read service, repository path, or
parameter, but it must not retain the caller-owned fragment or assemble the
original payload.

The semantic fields live in a simple mutable inner `Data` class owned by
`InitialRequestPayloadContinuation`. Completed service, repository path, host,
and extra parameters are written into that object as their delimiters arrive.

Parsing is a byte-at-a-time lexical state machine. It reuses one small byte
buffer for the current value, changes phase on `SP`, `NUL`, and `=`, and
materializes a `String` only when that value is complete. A byte buffer is used
instead of a char buffer so fragmented multibyte UTF-8 repository paths are not
decoded incorrectly.

The bounded slice prevents stage parsing from reading into the next pkt-line
header when multiple pkt-lines arrive in one network buffer.

### Outbound and PACK handling are outside this plan

Outbound responses will use a session-scoped, ordered `GitResponseSender` with
typed commands. It is a parallel executor, not a Continuation. Its contract
must be designed before the service-specific conversation graph is
implemented, while preserving the option to replace its queue with an actor
runtime later.

Initial push support will depend on a `PackReceiver` interface backed by a
bounded buffering implementation that invokes the existing `PackIngestor` on
completion. Replacing it with a true streaming PACK receiver is a future task.

## Known baseline

Commit `2d71cdc` introduced `ProtocolStage` and initially passed it from
`ControlHeaderContinuation` to a generic `PayloadContinuation`. Task 2 replaces
that intermediate node with a direct transition to
`InitialRequestPayloadContinuation`.

The repository-wide `make test` currently fails during `git-parser:testCompile`
because legacy tests still reference production APIs removed before this plan,
including `FrameConsumer`, `SemanticPhase`, capability parsers, receive-pack
parsers, and side-band classes. Restore that test-compilation baseline in a
separate cleanup commit before using the focused RED/GREEN commands below. Do
not restore the deleted implementation merely to satisfy obsolete tests.

---

### Task 1: Make control-header reading fragment-safe

**Files:**
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuation.java`
- Test:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuationTest.java`

**Step 1: Write the failing fragmented-header test**

Cover splits after header bytes one, two, and three. Each first fragment must
produce `Await`; the final fragment must decode the same DATA length and
transition to an `InitialRequestPayloadContinuation`.

Also cover two consecutive pkt-lines in one `ByteBuf` and verify that the first
header consumes exactly four bytes.

**Step 2: Run the test and verify RED**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=ControlHeaderContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the current Continuation does not retain a partial
header.

**Step 3: Implement bounded partial-header state**

Store at most four header bytes in a fixed scalar or fixed-size buffer. Do not
retain an input `ByteBuf`. Release or discard the partial state in `close()`.

When four bytes are available, call `ControlState.readControlType`, clear the
partial state, and transition while leaving all later input bytes unread.

**Step 4: Run the focused test and verify GREEN**

Run the same Maven command. Expected: PASS.

**Step 5: Commit**

```bash
git add \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuation.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuationTest.java
git commit -m "Read fragmented Git pkt-line headers"
```

After the commit, run `make test` outside the sandbox.

---

### Task 2: Route DATA directly to a stage-specific payload Continuation

**Files:**
- Modify:
  `docs/plans/2026-07-29-git-minimal-wire-inbound-framing.md`
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuation.java`
- Remove:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/PayloadContinuation.java`
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestPayloadContinuation.java`

**Step 1: Replace the generic payload transition**

Make the DATA branch in `ControlHeaderContinuation` switch on
`ProtocolStage`. For `INITIAL_REQUEST`, create
`InitialRequestPayloadContinuation(context, payloadLength)` directly.

Do not create a generic payload parser, packet router, or callback layer between
the control header and the stage-specific Continuation.

**Step 2: Move the current payload implementation**

Move the current implementation to `InitialRequestPayloadContinuation` and
remove its `ProtocolStage` field and constructor parameter. Its incremental
initial-request parsing replaces the temporary whole-payload behavior in the
next task.

**Step 3: Commit the approved boundary**

Use a single-line commit message describing the selected boundary. After the
commit, run `make test` outside the sandbox.

---

### Task 3: Make InitialRequestPayloadContinuation stream bounded fragments

**Files:**
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestPayloadContinuation.java`
- Remove when unused:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/StructuredPayloadContinuation.java`
- Test:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestPayloadContinuationTest.java`

**Step 1: Write failing streaming tests**

Cover:

1. payload split across at least three input buffers;
2. payload followed by another pkt-line in the same input buffer;
3. zero-length DATA payload (`0004`);
4. fragmented service, path, and parameter fields;
5. payload completion emitted exactly once;
6. no allocation proportional to payload length;
7. close during an incomplete payload without retained `ByteBuf` leakage;
8. stage parser failure transitions to `CompletedError`.

Use `CountingByteBufAllocator` to prove that
`InitialRequestPayloadContinuation` no longer allocates a full-payload buffer.

**Step 2: Run the test and verify RED**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=InitialRequestPayloadContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the current implementation allocates and assembles
`partialPayload`.

**Step 3: Implement the minimal streaming loop**

Replace `payloadLength` plus `partialPayload` with `remainingBytes` and
incremental initial-request parser state. On every call, expose at most
`remainingBytes` through `readSlice`, consume that slice synchronously, and
return:

- `Await` when the current input is exhausted and payload bytes remain;
- `Continue` only when the same Continuation has useful input left to process;
- `Transition` to the next Continuation after the last payload byte;
- a transition to `CompletedError` on parsing failure.

Never retain or release the caller-owned input or fragment.

**Step 4: Remove the obsolete whole-payload dispatch**

Delete `StructuredPayloadContinuation` after initial-request parsing moves into
`InitialRequestPayloadContinuation`. Do not replace it with `GitPacket` or
another whole-payload wrapper.

**Step 5: Run the focused tests and verify GREEN**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=ControlHeaderContinuationTest,InitialRequestPayloadContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 6: Commit**

```bash
git add \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation
git commit -m "Stream Git pkt-line payload fragments"
```

After the commit, run `make test` outside the sandbox.

---

### Task 4: Verify the shared inbound framing slice

**Files:**
- Modify only if verification exposes a defect in the files from Tasks 1–3.

**Step 1: Run focused verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4 -q -pl core/git-parser -am
```

Expected: PASS after the legacy test-compilation baseline has been restored.

**Step 2: Run repository verification**

Run outside the sandbox:

```bash
make test
git diff --check
```

Expected: PASS and no whitespace errors.

**Step 3: Stop at the agreed boundary**

Do not add initial-request semantics, upload-pack negotiation, receive-pack
commands, outbound response commands, side-band output, or PACK ingestion in
this implementation slice. Those require their own approved plans.

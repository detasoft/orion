# Native Git Side-Band Multiplexing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Interleave DATA pack output with ordered PROGRESS and ERROR messages through one legacy side-band response and one backpressured transport.

**Architecture:** Extend `LegacySideBandResponse` with FIFO message copies and one shared side-band frame serializer. Each `advance()` drains accepted messages before pulling more pack DATA, fills the existing fixed output buffer, and yields at most one outbound submission.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion Continuations, Maven, JUnit 5, AssertJ

---

### Task 1: Specify ordered channel multiplexing

**Files:**
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`

**Step 1: Write the failing interleaving test**

Create a fragmenting `NativePackProducer`, advance it once, enqueue progress
and error messages through the response, and verify exact wire order:

```text
NAK, DATA(first), PROGRESS, ERROR, DATA(remaining), FLUSH
```

Also verify that pack production continues after the ERROR frame.

**Step 2: Run the focused test to verify it fails**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser \
  -am -Dtest=GitNativeClientOutputTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure because `progress(ByteBuf)` and
`error(ByteBuf)` do not exist.

### Task 2: Implement one-buffer FIFO multiplexing

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`

**Step 1: Add message acceptance**

Add `progress(ByteBuf)` and `error(ByteBuf)` methods to
`LegacySideBandResponse`. Copy readable bytes into response-owned buffers,
append them to one FIFO, and return `SendResult.Failed` for a closed response
or an expected copy failure.

**Step 2: Add shared frame serialization**

Replace the pack-only packet writer with explicit selection:

```java
if (currentMessage != null || !messages.isEmpty()) {
    writeMessagePacket();
} else {
    writePackPacket();
}
```

Both paths write `[length][channel][payload]` into the same `output` buffer.
Retain a message offset when one message spans frames.

**Step 3: Preserve response lifecycle**

Do not treat ERROR as terminal. Release the current message and queued copies
on normal completion, explicit close, and serialization failure. Continue to
close the pack producer exactly once.

**Step 4: Run the focused test to verify it passes**

Run the focused command from Task 1.

Expected: all `GitNativeClientOutputTest` tests pass.

### Task 3: Cover FIFO, fragmentation, and cleanup

**Files:**
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`

**Step 1: Add meaningful non-trivial tests**

Add tests for:

- FIFO ordering of multiple PROGRESS and ERROR messages;
- a message larger than one maximum side-band payload;
- copied input remaining stable after caller mutation/release;
- queued message copies being released when the response closes.

**Step 2: Run focused parser tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser \
  -am -Dtest=GitNativeClientOutputTest,UploadResponseContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all selected tests pass.

### Task 4: Verify and finish tracking

**Files:**
- Modify: `TASKS.md`

**Step 1: Run routine development verification**

Run:

```bash
mvn verify -Pdev -T 4
```

Expected: reactor verification succeeds.

**Step 2: Finish the high-level task**

Mark the multiplexing task complete and remove its owner line. Preserve all
other task text and owners.

**Step 3: Inspect the final diff**

Confirm that implementation changes are limited to the output, its tests,
this task's plans, and this task's lines in `TASKS.md`. Leave the unrelated
receive-pack work unstaged.

No implementation commit is included because the user did not request one.

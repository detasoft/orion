# ByteBuf Read Checkpoint Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a transactional `ByteBuf` reader with rollback-on-close semantics and focused unit tests.

**Architecture:** Introduce a package-local `CheckpointedByteBufReader` that wraps a caller-owned Netty `ByteBuf`, exposes a narrow set of relative read primitives, and restores the original `readerIndex` unless committed. Existing parsers are not migrated in this change.

**Tech Stack:** Java, Netty `ByteBuf`, JUnit 5, AssertJ, Maven `-Pdev`.

---

### Task 1: Checkpoint Reader Tests

**Files:**
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/CheckpointedByteBufReaderTest.java`

**Step 1: Write failing tests**

Cover:
- uncommitted close rolls the wrapped buffer back to the starting `readerIndex`;
- committed close preserves consumed bytes;
- primitive reads expose unsigned byte and int reads through the checkpoint;
- `readRetainedSlice` advances the wrapped reader and gives caller-owned retained content;
- double commit is harmless.

**Step 2: Run test to verify it fails**

Run: `mvn test -Pdev -q -pl core/git-parser -Dtest=CheckpointedByteBufReaderTest`

Expected: compilation fails because `CheckpointedByteBufReader` does not exist.

### Task 2: Checkpoint Reader Implementation

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/CheckpointedByteBufReader.java`

**Step 1: Implement minimal API**

Expose:
- `static CheckpointedByteBufReader open(ByteBuf input)`;
- `int readableBytes()`;
- `boolean isReadable()`;
- `int readerIndex()`;
- `int readUnsignedByte()`;
- `int readInt()`;
- `void skipBytes(int length)`;
- `ByteBuf readRetainedSlice(int length)`;
- `void commit()`;
- `void close()`.

**Step 2: Run targeted tests**

Run: `mvn test -Pdev -q -pl core/git-parser -Dtest=CheckpointedByteBufReaderTest`

Expected: PASS.

**Step 3: Run module verification**

Run: `mvn verify -Pdev -q -pl core/git-parser`

Expected: PASS.

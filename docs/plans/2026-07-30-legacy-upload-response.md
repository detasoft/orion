# Legacy Upload Response Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build and send a negotiated native Git pack from `UploadResponseContinuation` using side-band-64k.

**Architecture:** Carry the accepted response capability alongside the typed fetch request, return a stateful `NativePackProducer` from the repository, and pull pack bytes into one side-band output chunk per Yield. The incremental builder uses `ByteBuf`, `Deflater`, and a running SHA-1 digest without Java stream APIs or a complete pack byte array.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion Continuations, Maven, JUnit 5, AssertJ

---

### Task 1: Replace the array pack builder with an incremental producer

**Files:**
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/NativePackProducer.java`
- Modify: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/NoDeltaPackBuilder.java`
- Modify: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack/NoDeltaPackBuilderTest.java`

1. Replace array-returning tests with a helper that repeatedly calls `produce(ByteBuf)` using tiny buffers and concatenates only in the test.
2. Run the focused builder test and confirm it fails for the missing producer API.
3. Implement stateful header, object-header, incremental deflate, and checksum phases.
4. Run the focused builder test and confirm it passes.

### Task 2: Add repository fetch

**Files:**
- Modify: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryServiceTest.java`

1. Update repository tests to consume `NativePackProducer` through small `ByteBuf` fragments.
2. Return `NativePackProducer` from repository and service fetch methods.
3. Verify that a `have` closure is excluded from the produced pack.
4. Run the focused service test and confirm it passes.

### Task 3: Pull the producer through side-band output

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`

1. Add tests proving that one streaming task submits at most one buffer and later advances continue from the same producer.
2. Replace the byte-array side-band operation with producer-backed response state.
3. Keep typed DATA, PROGRESS, and ERROR channel selection.
4. Run focused output tests.

### Task 4: Resume UploadResponseContinuation across chunks

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/LegacyUploadNegotiation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadResponseContinuation.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadResponseContinuationTest.java`

1. Preserve enough request data at the response boundary to calculate the accepted capability intersection and resolve the repository.
2. Following the repository exception for Continuations, implement production continuation logic before its tests.
3. For `side-band-64k`, create the producer once and advance channel-1 output once per `process` call.
4. For every other format, throw `IllegalStateException("not implemented")` inside the continuation boundary and convert it to terminal error flow.
5. Add tests for completed output, streaming output, exact side-band bytes, and unsupported format.
6. Run the focused continuation tests and confirm they pass.

### Task 5: Verify and update tracking

**Files:**
- Modify: `TASKS.md`

1. Run all focused parser tests outside the sandbox.
2. Run `mvn verify -Pdev -T 4` outside the sandbox.
3. Update the active task status to the next unfinished server slice while preserving its owner.
4. Inspect `git diff` and `git status --short`, leaving unrelated receive-pack changes untouched and unstaged.

No commit is included because the user did not request one.

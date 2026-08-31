# Blocking Git Native Client Output Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the continuation-aware native Git client output path with a blocking writer suitable for virtual-thread transports.

**Architecture:** Keep Git wire serialization in `GitNativeClientOutput`, but write synchronously to `BufferedByteOutput`. Remove the async output buffer coordinator and `SendResult`; continuation classes translate output exceptions into `ContinuationFlow` errors at their boundary.

**Tech Stack:** Java, Netty `ByteBuf`, Orion `BufferedByteOutput`, Maven/JUnit.

---

### Task 1: Blocking Output Contract

**Files:**
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`

**Step 1: Write the failing test**

Add a test that serializes a response larger than 64 KiB through a `BufferedByteOutput` and asserts the full wire payload is written synchronously.

**Step 2: Run the focused test**

Run: `mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitNativeClientOutputTest`

Expected: fail before the constructor/behavior exists.

**Step 3: Implement the blocking writer**

Replace the coordinator path with a fixed local buffer flushed through `BufferedByteOutput.write` and `flush`.

**Step 4: Run the focused test**

Run: `mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitNativeClientOutputTest`

Expected: pass after migration.

### Task 2: Remove Async Output Adapter

**Files:**
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/output/GitOutputBufferCoordinator.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/output/DoubleGitOutputBufferCoordinator.java`
- Delete: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/output/DoubleGitOutputBufferCoordinatorTest.java`
- Delete: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/output/GitOutputBufferCoordinatorContractTest.java`
- Modify: continuation tests that asserted `SendResult.Streaming`

**Step 1: Remove obsolete tests**

Delete coordinator-specific tests and replace streaming assertions with blocking completion assertions where the behavior still matters.

**Step 2: Remove production adapter classes**

Delete the coordinator classes and unused async constructors/imports.

**Step 3: Run focused parser tests**

Run: `mvn test -Pdev -T 4 -q -pl git/git-parser -Dtest=GitNativeClientOutputTest,ProtocolV2PackfileResponseTest`

Expected: pass.

### Task 3: Verify Module

**Files:**
- All touched parser and transport files.

**Step 1: Run module verification**

Run: `mvn test -Pdev -T 4 -q -pl git/git-parser`

Expected: pass.

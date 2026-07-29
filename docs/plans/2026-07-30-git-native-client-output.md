# Git Native Client Output Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a typed, non-blocking output operation for legacy upload-pack advertisement.

**Architecture:** `GitNativeClientOutput` encodes a typed advertisement directly into a caller-owned, fixed-size 64 KiB `ByteBuf` when the complete result fits. Continuations decide between transition and yield from the boolean result; Netty orchestration remains outside this class.

**Tech Stack:** Java 21, Netty `ByteBuf`, JUnit 5, AssertJ

---

### Task 1: Add typed legacy advertisement output

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/advertisement/GitV1Advertisement.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/capability/GitCapability.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`

**Step 1:** Write tests for protocol encoding and an unchanged buffer when capacity is insufficient.

**Step 2:** Run the focused test and verify that it fails because the new API is absent.

**Step 3:** Implement the typed value and the single `sendAdvertisement` operation.

**Step 4:** Run the focused test and the `git-parser` test suite.

### Task 2: Expose the output through the wire context

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: relevant context construction tests.

**Step 1:** Add the output to `GitMinimalWireMachine.Context` while preserving a convenient facade constructor.

**Step 2:** Run the focused continuation tests.

Integration into `v0v1.UploadPackContinuation` and Netty queue draining follows
after the advertisement source is connected to repository resolution.

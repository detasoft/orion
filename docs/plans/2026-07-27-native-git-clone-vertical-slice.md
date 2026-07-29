# Native Git Clone Vertical Slice Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Serve a protocol v2 Git CLI clone from a native in-memory repository with native ref lookup, object traversal, and no-delta pack generation.

**Architecture:** Add upload-pack-specific protocol and storage-facing classes to `git-native-storage`, while preserving `GitRepository.upload()` as the engine boundary. Reuse `git-parser` pkt-line and side-band primitives, keep receive-pack files untouched, and pause if implementation requires overlapping edits to the active receive-pack work.

**Tech Stack:** Java 21, Maven `-Pdev`, JUnit 5, AssertJ, Netty `ByteBuf`, Git CLI fixtures

---

### Task 1: Reuse native repository read operations

**Files:**

- Use: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/ref/LooseRefStore.java`
- Use: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/object/LooseObjectStore.java`
- Verify: existing ref and object store tests

**Steps:**

1. Confirm `LooseRefStore.snapshot()` supplies an immutable ref snapshot.
2. Confirm `LooseObjectStore.read()` supplies typed uncompressed object data.
3. Reuse these operations without changing receive-pack storage files.

### Task 2: Protocol v2 ref advertisement

**Files:**

- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/GitUploadPackException.java`
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeLsRefsService.java`
- Test: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/upload/NativeLsRefsServiceTest.java`

**Steps:**

1. Write a failing test for `HEAD` symref, branch and tag ordering.
2. Run the focused test and confirm `NativeLsRefsService` is missing.
3. Implement `ls-refs` output with `GitPktLineWriter`.
4. Write and verify a failing empty/unborn repository test.
5. Implement empty/unborn output without inventing an object id.
6. Run all `git-native-storage` tests.

### Task 3: Object closure traversal

**Files:**

- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeObjectClosure.java`
- Test: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/upload/NativeObjectClosureTest.java`

**Steps:**

1. Write a failing commit-tree-blob closure test using canonical Git object bytes.
2. Run the focused test and confirm the closure type is missing.
3. Implement straightforward commit header and binary tree entry traversal.
4. Add a failing test where a have commit removes its reachable closure.
5. Implement have-closure subtraction.
6. Add a failing missing-object test, then return a typed failure.
7. Run all `git-native-storage` tests.

### Task 4: No-delta pack builder

**Files:**

- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/NoDeltaPackBuilder.java`
- Test: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack/NoDeltaPackBuilderTest.java`

**Steps:**

1. Write a failing test for a one-object PACK v2 stream and SHA-1 trailer.
2. Run the focused test and confirm the builder is missing.
3. Implement pack header, variable-length object headers, zlib bodies, and trailer.
4. Add a multi-object test with deterministic object-id ordering.
5. Validate generated packs with `git index-pack --stdin`.
6. Run all `git-native-storage` tests.

### Task 5: Minimal protocol v2 fetch service

**Files:**

- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeUploadPackService.java`
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeFetchRequest.java`
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeFetchRequestParser.java`
- Test: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/upload/NativeUploadPackServiceTest.java`

**Steps:**

1. Write a failing test that parses one want, optional haves, `done`, and `side-band-64k`.
2. Implement the minimal request parser over existing pkt-line primitives.
3. Add failing tests for invalid ids, unsupported filter, and packet limit.
4. Implement typed request failures.
5. Write a failing service test proving access checks run before closure traversal.
6. Implement access validation, closure enumeration, pack building, and side-band output.
7. Write a failing test for exactly-once successful upload statistics.
8. Implement successful completion accounting and run module tests.

### Task 6: Native repository and stream transport integration

**Files:**

- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Test: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java`
- Test: `core/git-engine/src/test/java/pro/deta/orion/git/NativeGitCloneCompatibilityTest.java`

**Steps:**

1. Write a failing repository test for `GitRepository.upload()` delegation and fetch access propagation.
2. Implement the smallest native repository adapter without changing `GitRepository`.
3. Write a failing compatibility test that serves repository streams and runs `git clone --config protocol.version=2`.
4. Connect the test transport to `GitInternalService` and the native repository provider.
5. Confirm the clone checks out the expected file and ref.
6. Add an empty repository clone scenario.
7. Run `mvn test -Pdev -q -pl core/git-native-storage,core/git-engine -am`.

### Task 7: Final verification and task tracking

**Files:**

- Modify: `TASKS.md`

**Steps:**

1. Review `git diff` and confirm no active receive-pack file was modified.
2. Run `mvn verify -Pdev` from the repository root outside the sandbox.
3. Mark the clone vertical slice complete and remove its owner line.
4. Report any unrelated pre-existing working-tree changes separately.

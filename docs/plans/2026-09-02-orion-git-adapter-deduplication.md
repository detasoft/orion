# Orion Git Adapter Deduplication Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace Orion adapter copies of parser, pack-ingestion, pack-production, and advertisement behavior with
small production-owned APIs while preserving the reviewed interoperability behavior.

**Architecture:** Repository-path syntax stays in `git-parser`; pack state and streaming stay in
`git-native-storage`; advertised-ref lookup stays in `git-client`. The test-only adapter composes those APIs and keeps
only workflow orchestration. Existing wire code consumes the same native-storage helpers so the extracted contracts
have two real callers.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Netty buffers, Orion Git parser/client/native storage.

---

### Task 1: Expose canonical repository-path normalization

**Files:**
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireBootstrapTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireBootstrap.java`
- Modify: `tests/git-engine-orion-adapters/pom.xml`
- Modify: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitServer.java`

1. Add direct happy-path and invalid/traversal tests for `GitWireBootstrap.normalizeRepositoryPath`.
2. Run the focused parser test and record RED because the method is private.
3. Make the existing normalizer public without changing its implementation and rerun GREEN.
4. Add the direct parser dependency and replace adapter `.git` stripping with the canonical method.

### Task 2: Extract pack-ingestion output state

**Files:**
- Create: `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack/PackIngestionOutputTest.java`
- Create: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackIngestionOutput.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitWorkTree.java`
- Delete: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/NativePackTarget.java`

1. Add tests for successful completion/session close and failed or incomplete ingestion.
2. Run the focused native-storage test and record RED for the missing output driver.
3. Implement a `BufferedByteOutput`/`AutoCloseable` wrapper that owns result transitions, completion, and close.
4. Rerun GREEN, then migrate wire receive-pack and the adapter while retaining caller-specific protocol errors.

### Task 3: Add producer drain and advertised-ref lookup

**Files:**
- Create: `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack/NativePackProducerTest.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/NativePackProducer.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `git/git-client/src/test/java/pro/deta/orion/git/client/GitRemoteAdvertisementTest.java`
- Modify: `git/git-client/src/main/java/pro/deta/orion/git/client/GitRemoteAdvertisement.java`
- Modify: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitWorkTree.java`

1. Add producer tests for multi-step drain, no-progress rejection, and unchanged close ownership; record RED.
2. Add `writeTo(BufferedByteOutput)` and migrate both wire loops plus the adapter; rerun GREEN.
3. Add lookup tests for present, missing, and duplicate ordered refs; record RED.
4. Add `findRef(String)` with an ordinary loop and migrate the adapter without a map projection; rerun GREEN.

### Task 4: Verify and reform the final task commit

1. Run focused tests for parser, native storage, client, transport, and Orion adapters with `-Pdev -T 4 -am`.
2. Run `mvn verify -Pdev -T 4`, `git diff --check`, and the source line-length audit.
3. Amend/squash the branch to one commit with the existing required task subject and unchanged task-tree deletion.
4. Run post-commit `make test`, confirm one commit and a clean worktree, and stop before integration.

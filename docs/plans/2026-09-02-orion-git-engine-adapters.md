# Orion Git Engine Adapters Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add reusable test-only Orion Git client and server adapters and prove the required Orion-facing engine transfers.

**Architecture:** Create a dedicated adapter module above the engine-neutral workflow contracts and production Orion
Git modules. Implement all Orion workflow behavior with public native repository/client/transport primitives; use JGit
only for independent read-only endpoint snapshots.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Orion native Git storage/client/transport, JGit observation, canonical
Git control client/server.

---

### Task 1: Add the test-only adapter module and explicit factory surface

**Files:**
- Modify: `tests/pom.xml`
- Create: `tests/git-engine-orion-adapters/pom.xml`
- Create: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitEngines.java`
- Test: `tests/git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/OrionGitEnginesTest.java`

1. Add a test that requests an Orion client and server, asserts both engine names are `orion`, and asserts their
   declared capabilities contain every shared `GitCapability`.
2. Add the new Maven module and dependencies on `git-engine-test-support`, `git-client`, `git-native-storage`, and
   `git-transport`.
3. Run `mvn test -Pdev -T 4 -q -pl tests/git-engine-orion-adapters -am
   -Dtest=OrionGitEnginesTest -Dsurefire.failIfNoSpecifiedTests=false` outside the sandbox and confirm RED because the
   factory is absent.
4. Add the minimal public factory and explicit immutable capability constants, backed initially by adapter stubs.
5. Re-run the focused test and confirm GREEN.

### Task 2: Implement native local repository workflows

**Files:**
- Create: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitClient.java`
- Create: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitWorkTree.java`
- Test: `tests/git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/OrionGitClientTest.java`

1. Write tests showing `init` plus staged commit creates the deterministic native `main` commit, local ref updates can
   target `HEAD`, and unsupported/missing staged paths fail explicitly.
2. Run the focused test outside the sandbox and confirm RED from unsupported stub behavior.
3. Build local `.git` storage from public `LooseObjectStore`, `LooseRefStore`, and `NativeGitRepository`; write symbolic
   `HEAD`; implement staged file reads and deterministic `saveFiles` commits.
4. Implement remote registration, exact ref resolution, local compare-and-set ref updates, snapshots, and close.
5. Re-run the focused test and confirm GREEN; refactor only while green.

### Task 3: Implement Orion upload/receive-pack client macros

**Files:**
- Modify: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitClient.java`
- Modify: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitWorkTree.java`
- Create: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/NativePackTarget.java`
- Test: `tests/git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/OrionGitClientTest.java`

1. Write integration tests against the existing JGit and canonical Git server fixtures for initial native push, clone,
   fetch update, fast-forward pull, and multi-ref push. Add an explicit divergent pull test that leaves local `HEAD`
   unchanged.
2. Run the focused test outside the sandbox and confirm RED from missing network operations.
3. Implement structured `GitClientResult` failure conversion and receive-pack discovery/commands. Stream
   `NativePackProducer` output directly as `GitPackSource` using remote advertised tips as haves.
4. Implement upload-pack discovery and a `BufferedByteOutput` backed by `PackIngestionSession`. Publish the completed
   quarantine and update remote-tracking refs only after a complete successful pack.
5. Implement clone as init/fetch/local-main update and pull as fetch/production `NativeObjectClosure` ancestry
   validation/compare-and-set update.
6. Re-run the focused test and confirm GREEN.

### Task 4: Implement the isolated Orion native server adapter

**Files:**
- Create: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitServer.java`
- Modify: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitEngines.java`
- Test: `tests/git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/OrionGitServerTest.java`

1. Write tests for a fresh file-backed repository rooted below the invocation directory, symbolic `main`, actual
   `git://127.0.0.1:<bound-port>` URI, several provisioned repositories sharing only that server instance, and close.
2. Add a test that JGit pushes through the endpoint and that `snapshot` observes the result through a fresh read-only
   JGit clone.
3. Run the focused test outside the sandbox and confirm RED because the server stub cannot provision.
4. Lazily create `FileNativeGitRepositoryProvider` and `GitNativeTransportService` with
   `GitTransportConfig("127.0.0.1", 0)`, then provision the native repository before returning its URI.
5. Override snapshots: return the canonical empty `main` snapshot before a first push, otherwise clone the public URI
   with JGit without checkout, capture through `RepositorySnapshot`, and remove observer storage.
6. Stop the transport deterministically in `close`, and include bound endpoint/storage/running state in diagnostics.
7. Re-run the focused test and confirm GREEN.

### Task 5: Prove the required Orion-facing interoperability paths

**Files:**
- Create: `tests/git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/OrionEngineInteroperabilityTest.java`

1. Define shared deterministic push/clone/fetch and push/clone/fast-forward-pull scenarios using only workflow
   contracts.
2. Instantiate fresh resources for Orion/JGit, Orion/Git, Orion/Orion, JGit/Orion, and Git/Orion; assert the matrix
   requirement set is complete before returning invocations.
3. Require a non-trivial update commit in every scenario and verify final refs, ancestry, tree mode, and content hash
   through `RepositorySnapshot`; require Orion/Orion's server snapshot to be the independent JGit observation.
4. Run `mvn test -Pdev -T 4 -q -pl tests/git-engine-orion-adapters -am
   -Dtest=OrionEngineInteroperabilityTest -Dsurefire.failIfNoSpecifiedTests=false` outside the sandbox and confirm RED
   for any missing adapter behavior.
5. Apply only minimal adapter corrections, reproducing each behavior failure before changing production adapter code,
   until the focused matrix is GREEN.

### Task 6: Commit and verify the task branch

1. Run `mvn test -Pdev -T 4 -q -pl tests/git-engine-orion-adapters -am
   -Dsurefire.failIfNoSpecifiedTests=false` outside the sandbox.
2. Inspect `git diff --check`, the complete change set, and source line lengths; commit the logical implementation with
   a one-line subject.
3. Run the required post-commit `make test` outside the sandbox.
4. Run `mvn verify -Pdev -T 4` outside the sandbox.
5. Apply the blocking rules in `docs/reviews/RULES.md`, inspect branch history/status, and prepare the complete review
   packet without squashing, deleting task-tree nodes, transferring to main, or cleaning up the worktree.

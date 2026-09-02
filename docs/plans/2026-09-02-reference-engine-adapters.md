# Reference Git Engine Adapters Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add deterministic reusable JGit and canonical Git adapters and prove all four reference control pairs.

**Architecture:** Extend the existing workflow contracts only where the planned scenarios need ref operations and
diagnostics. Reuse one bounded canonical Git process runner, and keep each server implementation isolated behind
`GitServers` factories.

**Tech Stack:** Java 21, JGit 7, canonical Git CLI/daemon, JUnit 5, AssertJ, Maven.

---

### Task 1: Extend the shared adapter contracts

**Files:**
- Modify: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitClient.java`
- Modify: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitServer.java`
- Modify: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitWorkTree.java`
- Modify: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitInteroperabilityHarness.java`
- Test: `tests/git-engine-test-support/src/test/java/pro/deta/orion/git/workflow/GitInteroperabilityMatrixTest.java`

1. Write failing tests for diagnostic augmentation and the new ref operation surface.
2. Run the focused module test and confirm the expected compile/test failure.
3. Add minimal diagnostics defaults, local ref update, and multi-ref push contracts.
4. Make the harness append client/server diagnostics to scenario failures.
5. Run the focused tests and commit the contract change.

### Task 2: Rename and harden the canonical Git client

**Files:**
- Rename: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/NativeGitWorkflowClient.java`
- Create: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitCommandRunner.java`
- Modify: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitClients.java`
- Test: `tests/git-engine-test-support/src/test/java/pro/deta/orion/git/workflow/GitCliWorkflowClientTest.java`

1. Write failing tests for deterministic commits, ref updates, configuration isolation, version diagnostics, and a
   clear missing-executable prerequisite failure.
2. Run the focused tests and confirm failures are caused by missing behavior.
3. Implement the bounded runner and `GitCliWorkflowClient` using per-command deterministic configuration.
4. Run focused tests, refactor common process code while green, and commit.

### Task 3: Generalize the JGit adapters

**Files:**
- Modify: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/JGitWorkflowClient.java`
- Create: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/JGitDaemonServer.java`
- Create: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitServers.java`
- Test: `tests/git-engine-test-support/src/test/java/pro/deta/orion/git/workflow/JGitReferenceAdaptersTest.java`

1. Write failing tests for deterministic commits, ref updates, version diagnostics, receive-pack, and dynamic ports.
2. Run them and confirm the missing-server/ref-operation failures.
3. Implement the JGit client operations and isolated daemon server.
4. Run focused tests and commit.

### Task 4: Add the canonical Git daemon server

**Files:**
- Create: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitDaemonServer.java`
- Modify: `tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitServers.java`
- Test: `tests/git-engine-test-support/src/test/java/pro/deta/orion/git/workflow/GitDaemonServerTest.java`

1. Write failing tests for receive-pack, isolated roots, dynamic ports, lifecycle cleanup, and diagnostics.
2. Run them and confirm failure because the server is absent.
3. Implement bounded retrying startup, readiness probes, captured logs, and bounded shutdown.
4. Run focused tests and commit.

### Task 5: Prove the four reference combinations

**Files:**
- Create: `tests/git-engine-test-support/src/test/java/pro/deta/orion/git/workflow/ReferenceEngineSmokeMatrixTest.java`

1. Write a shared smoke scenario covering init, commit, clone, fetch, fast-forward pull, local ref update, and push.
2. Run all four pairs and inspect any interoperability failures.
3. Apply the smallest adapter fixes, rerunning each failing test first when behavior changes.
4. Run the complete module test and commit.

### Task 6: Verify the repository

1. Run `mvn test -Pdev -T 4 -q -pl tests/git-engine-test-support -am` outside the sandbox.
2. Run `mvn verify -Pdev -T 4` outside the sandbox.
3. Inspect `git diff --check`, task-branch history, status, and changed files.
4. Report results for review without squashing, transferring, or cleaning up the task worktree.

# Git Minimal Wire Handler JGit User Test Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a semi-real user test that drives `GitMinimalWireHandler` through JGit over a loopback `git://` connection, pushes an initial commit, closes, reconnects, and fetches that commit.

**Architecture:** The test owns a temporary Netty server. Each accepted connection receives a fresh `GitMinimalWireMachine` and `GitMinimalWireHandler`, while all sessions share one `InMemoryNativeGitRepositoryProvider` so repository state survives disconnects.

**Tech Stack:** JUnit 5, AssertJ, JGit, Netty NIO transport, Orion native git parser/storage.

---

### Task 1: Add the Failing User Test

**Files:**
- Create: `net/git-transport/src/test/java/pro/deta/orion/transport/git/netty/GitMinimalWireHandlerJGitUserTest.java`

**Step 1:** Write a JUnit test that:
- starts a loopback Netty server with `GitMinimalWireHandler`;
- creates a local JGit source repository;
- commits `README.md`;
- pushes `master` to `git://127.0.0.1:<port>/project`;
- closes that client connection;
- creates a second local JGit repository;
- fetches `refs/heads/master` from the same URL;
- asserts that a second server connection was opened and that the fetched commit contains `README.md`.

**Step 2:** Run:

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am -Dtest=GitMinimalWireHandlerJGitUserTest
```

Expected: FAIL if the handler/server integration is incomplete or the new test does not compile yet.

### Task 2: Minimal Integration Fixes

**Files:**
- Modify only production files required by the failing test, if any.

**Step 1:** Fix only the missing integration behavior revealed by Task 1.

**Step 2:** Re-run the focused test command.

Expected: PASS.

### Task 3: Verification

**Step 1:** Run:

```bash
mvn verify -Pdev -T 4 -pl net/git-transport -am
```

Expected: PASS, unless unrelated existing work in the dirty tree fails compilation or tests.

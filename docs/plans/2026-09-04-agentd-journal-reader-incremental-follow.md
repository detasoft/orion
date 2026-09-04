# Incremental AgentD Journal Follow Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extend the completed filesystem journal reader with bounded pages, disposable seek positions, and
100 millisecond fallback wakeups so live active segments are not rescanned from their beginning.

**Architecture:** Preserve the existing stateless snapshot API and add an incremental page API over the same
segment discovery and structural decoder. A returned opaque position caches validated physical progress only;
server event IDs remain the recovery authority and invalidate the hint after replacement or retention.

**Tech Stack:** Java 21, NIO channels and WatchService, agent-protocol CBOR decoding, zstd-jni, JUnit 5, AssertJ.

---

Follow @superpowers:test-driven-development per task. Use @superpowers:requesting-code-review after each task and
@superpowers:verification-before-completion before final task-tree cleanup and transfer.

### Task 1: Reconcile dependencies and native record limits after rebase

**Files:**
- Modify: `pom.xml`
- Modify: `agentd/pom.xml`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Steps:**

1. Add a failing reader test containing a valid native record whose 16 MiB payload makes the encoded record exceed
   the ordinary Agent frame limit.
2. Run `make run-test MODULE=agentd TEST='FileSystemSessionJournalReaderTest'` and confirm a limit issue.
3. Change both reader decoder construction sites from `AgentProtocolLimits.defaults()` to
   `AgentProtocolLimits.journalDefaults()`.
4. Remove the duplicate Commons Compress and unversioned `zstd-jni` additions from the rebased branch. Keep the
   completed reader's direct `${zstd-jni.version}` dependency and root version 1.5.7-11.
5. Run the focused reader test, `git diff --check`, commit, then run post-commit `make test`.

### Task 2: Add bounded pages and reusable physical positions

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadLimits.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadPosition.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadBoundary.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadPage.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/SessionJournalReader.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Steps:**

1. Write failing contract tests for positive limits, immutable results, optional initial cursor, page-limit boundary,
   incomplete-tail boundary, explicit gap/issue boundaries, and cursor/position mismatch.
2. Write failing filesystem tests for record and encoded-byte page limits, lossless continuation, and a position
   whose raw offset remains at the beginning of a partial item.
3. Implement immutable contracts. Require `maxEncodedBytes` to fit one maximum legal journal record so every page
   can make progress. Keep physical position fields package-private and expose only `lastEventId()` publicly.
4. Refactor scanning so the existing `readAfter` behavior remains unchanged while `readPage` retains only records
   within limits and returns the exact boundary after the last retained record.
5. Add failing tests proving that a second active-tail read does not inspect the validated prefix, crosses raw
   rotation, and falls back by event ID after raw-to-Zstd replacement or retained-segment deletion.
6. Implement raw `FileChannel.position(offset)` continuation. Cache the oldest segment number/first ID and
   positioned file key in the opaque position. Reuse it only when segment identity and size remain compatible;
   otherwise rediscover once from the logical cursor.
7. Stream through any unretained remainder only when needed to preserve the existing actual
   `lastAvailableEventId`; never store records beyond page limits. Reopening a compressed segment may replay to a
   decoded offset.
8. Run `make run-test MODULE=agentd TEST='FileSystemSessionJournalReaderTest'`, commit, then run `make test`.

### Task 3: Add event-driven availability with a 100 millisecond fallback

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalAvailabilityMonitor.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/journal/JournalAvailabilityMonitorTest.java`

**Steps:**

1. Write fake-event-source tests for create/modify/delete events, overflow, periodic timeout, invalid-key
   re-registration, close, and a default interval of exactly 100 milliseconds.
2. Run `make run-test MODULE=agentd TEST='JournalAvailabilityMonitorTest'` and confirm failure.
3. Implement an `AutoCloseable` waiter using `WatchService.poll`. It owns no worker, cursor, or reader and returns a
   trigger for every event or timeout.
4. Add a real-directory test: read a partial active record, wait, append its remaining bytes, then ensure a wakeup
   followed by `readPage` returns the completed record within a generous one-second deadline.
5. Run `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.*Test'`, commit, then run `make test`.

### Task 4: Verify, review, and finish the follow-up task

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/package-info.java`
- Modify: `docs/plans/current-work/agentd/TASK.md`
- Delete: `docs/plans/current-work/agentd/journal-reader-incremental-follow/TASK.md`

**Steps:**

1. Update the package-level comment to distinguish stateless snapshots from bounded incremental following.
2. Run `git diff --check` and
   `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.*Test'`.
3. Run `mvn verify -Pdev -T 4` and request a final whole-range code review under `docs/reviews/RULES.md`.
4. Fix every Critical or Important finding and repeat affected verification.
5. Squash task-branch commits to
   `Follow AgentD session journals incrementally [task: agentd/journal-reader-incremental-follow]`, deleting the leaf
   task and parent link in that commit.
6. Rebase onto current `main`, cherry-pick the squashed commit to `main`, run post-commit `make test`, then remove the
   worktree and branch only after verifying a clean result.

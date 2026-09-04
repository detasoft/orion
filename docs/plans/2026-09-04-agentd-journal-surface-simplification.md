# AgentD Journal Surface Simplification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove unused AgentD journal APIs and make bounded reading and availability notification expose only
the state required by the future replication pump.

**Architecture:** `FileSystemSessionJournalReader` keeps one paged scan path. A page ends immediately at its
limit, a gap is a terminal empty result, and physical positions remain opaque continuation state. The filesystem
monitor coalesces every usable watch/poll condition into a rescan signal and distinguishes only closure.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Java NIO WatchService, CBOR Sequence decoder, Maven.

---

### Task 1: Make page limits and gaps terminal

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadPage.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Step 1: Write failing page-contract tests**

Change `fallsBackAndReportsGapAfterPositionedSegmentRetention` and the direct `JournalReadPage` constructor
coverage to require `GAP` pages to contain no records and no `nextPosition`. Add a bounded-page case whose first
record fills the limit and whose later segment is corrupt; require the first result to be `PAGE_LIMIT` with only
the first record and no issue, then require a continuation to surface the later corruption.

```java
assertThat(gap.records()).isEmpty();
assertThat(gap.nextPosition()).isEmpty();
assertThat(gap.gap()).contains(new JournalCursorGap(new EventId(1), new EventId(2)));

assertThat(first.boundary()).isEqualTo(JournalReadBoundary.PAGE_LIMIT);
assertThat(first.records()).extracting(SessionEventRecord::eventId)
        .containsExactly(new EventId(1));
assertThat(first.issue()).isEmpty();
```

**Step 2: Run the focused tests and verify RED**

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest'`

Expected: FAIL because a gap currently returns retained records and the scan continues after reaching a page
limit.

**Step 3: Stop at the first terminal boundary**

Make `PageAccumulator.accept` return `false` immediately when the next record cannot fit. Propagate that stop
through the current segment and segment loop without turning it into an issue. Once the retained first event is
known to follow the requested cursor, construct the `GAP` result before collecting records and omit its physical
position. Strengthen `JournalReadPage` invariants so `GAP` rejects records and positions.

**Step 4: Remove the exact-tail result contract**

Delete `lastAvailableEventId` from `JournalReadPage`. Keep `firstAvailableEventId` only as the retained-floor fact
needed for gap reporting. At `COMPLETE`, callers can derive the last delivered event from records or the opaque
position; at `PAGE_LIMIT`, no global tail is promised or computed.

**Step 5: Run the focused tests and verify GREEN**

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest'`

Expected: PASS with no record returned at `GAP` and no scan past `PAGE_LIMIT`.

**Step 6: Commit the page contract**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadPage.java \
  agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java \
  agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java
git commit -m "Stop AgentD journal reads at page boundaries"
```

### Task 2: Delete the snapshot reader and redundant interface

**Files:**
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadResult.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/journal/SessionJournalReader.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/package-info.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`
- Create if useful: `agentd/src/test/java/pro/deta/orion/agentd/journal/JournalPageTestReader.java`

**Step 1: Convert unique snapshot coverage to the paged API**

Replace each `readAfter` test with direct `readPage` assertions or a test-only loop that repeatedly supplies
`nextPosition` and its `lastEventId`. Preserve coverage for unsigned ordering, holes, raw/compressed replacement,
incomplete active tails, corrupt closed tails, unknown payload bytes, stale-snapshot retry, concurrent append, and
retention advance. Do not create a second production accumulator or public whole-journal reader.

The test helper, if needed, must stop on `COMPLETE`, `INCOMPLETE_TAIL`, `GAP`, or `ISSUE`, and may only aggregate
records already returned by pages.

**Step 2: Run the converted tests and verify they pass before deletion**

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest'`

Expected: PASS while the old production snapshot API still exists but has no remaining test caller.

**Step 3: Delete the unused production concepts**

Remove `readAfter`, `readSnapshot`, `scanSegments`, `readSegment`, snapshot `accept`, and `Accumulator` from
`FileSystemSessionJournalReader`. Delete `JournalReadResult`. Re-run `rg` across production and tests; when there
is still exactly one reader implementation and no polymorphic consumer, delete `SessionJournalReader` and its
`implements`/`@Override` declarations. Update the package comment to describe bounded incremental reading only.

**Step 4: Verify the deletion**

Run:

```bash
rg -n "readAfter|JournalReadResult|class Accumulator|SessionJournalReader" agentd/src
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest'
```

Expected: `rg` finds nothing and the focused tests PASS.

**Step 5: Commit the single reader path**

```bash
git add agentd/src/main agentd/src/test/java/pro/deta/orion/agentd/journal
git commit -m "Remove the AgentD journal snapshot reader"
```

### Task 3: Coalesce journal availability wake-ups

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalAvailabilityMonitor.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/journal/JournalAvailabilityMonitorTest.java`

**Step 1: Write failing coalescing tests**

Replace kind/path assertions with a two-outcome contract such as `Wakeup.RESCAN` and `Wakeup.CLOSED`. Feed one
watch key several create/modify/delete/overflow events and assert one `RESCAN`; on the next `await`, assert that
the event source polls again instead of draining a queued path. Keep tests for periodic fallback, failed and
successful re-registration, close-unblocks-await, invalid path contexts, and completing a partial active record.

```java
assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
assertThat(events.pollCount()).hasValue(1);
assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
assertThat(events.pollCount()).hasValue(2);
```

**Step 2: Run the monitor test and verify RED**

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.JournalAvailabilityMonitorTest'`

Expected: compilation or assertions FAIL because `TriggerKind`, `Trigger`, relative paths, and the pending queue
still form the public result.

**Step 3: Implement one coalesced signal**

Replace `TriggerKind` and `Trigger` with `Wakeup { RESCAN, CLOSED }`. Drain all events from each ready key without
retaining their paths or kinds; any recognized event, overflow, timeout, successful/failed re-registration, or
unusable context yields `RESCAN`. Preserve `CLOSED` as the stable result after `close`. Delete the `ArrayDeque`
and `Queue` state.

**Step 4: Run both journal test classes and verify GREEN**

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.*Test'`

Expected: PASS.

**Step 5: Commit the monitor simplification**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/journal/JournalAvailabilityMonitor.java \
  agentd/src/test/java/pro/deta/orion/agentd/journal/JournalAvailabilityMonitorTest.java
git commit -m "Coalesce AgentD journal availability wakeups"
```

### Task 4: Verify the AgentD journal surface

**Files:**
- Inspect: `agentd/src/main/java/pro/deta/orion/agentd/journal/`
- Inspect: `agentd/src/test/java/pro/deta/orion/agentd/journal/`

**Step 1: Check that the requested concepts are absent**

Run:

```bash
rg -n "readAfter|JournalReadResult|class Accumulator|TriggerKind|relativePath|lastAvailableEventId|SessionJournalReader" \
  agentd/src
```

Expected: no matches. If a newly integrated internal consumer gives `SessionJournalReader` real polymorphic value,
retain only that interface and report the evidence instead of deleting it speculatively.

**Step 2: Run focused verification**

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.*Test'`

Expected: PASS.

**Step 3: Run development verification**

Run: `mvn verify -Pdev -T 4`

Expected: BUILD SUCCESS.

**Step 4: Inspect the final diff**

Run: `git diff --check` and review `git diff <worker-base>...HEAD`.

Expected: no whitespace errors, no wire or persisted-byte changes, and a net reduction in production concepts and
branches.

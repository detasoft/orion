# AgentD Session Journal Reader Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a bounded JVM reader that returns exact session-host journal records after an unsigned event-ID
cursor across raw and Zstandard-compressed CBOR Sequence segments.

**Architecture:** Keep filesystem traversal and recovery policy in `agentd.journal`, while reusing the shared
`EventId`, `SessionEventDecoder`, and `SessionEventRecord` contracts from `agent-protocol`. Read segment streams in
small chunks, preserve valid prefixes with typed terminal issues, and retry once when retention or compression
invalidates a discovered snapshot.

**Tech Stack:** Java 21, NIO, `agent-protocol` CBOR Sequence decoder, zstd-jni, JUnit 5, AssertJ.

---

### Task 1: Define reader results and read one raw segment

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/SessionJournalReader.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadResult.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalCursorGap.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/JournalReadIssue.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Step 1: Write the failing raw-segment test**

Create two events with `SessionEventCodec`, append their encoded bytes to `00000001.cbor`, call
`readAfter(directory, Optional.empty())`, and assert:

```java
assertThat(result.records()).extracting(SessionEventRecord::eventId)
        .containsExactly(new EventId(1), new EventId(2));
assertThat(result.firstAvailableEventId()).contains(new EventId(1));
assertThat(result.lastAvailableEventId()).contains(new EventId(2));
assertThat(result.gap()).isEmpty();
assertThat(result.issue()).isEmpty();
assertThat(result.ignoredIncompleteTail()).isFalse();
```

Also mutate the source arrays after writing and assert `encodedRecord()` matches the original event bytes.

**Step 2: Run the focused test and verify it fails**

Run outside the sandbox:

```bash
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest'
```

Expected: FAIL because the reader types do not exist.

**Step 3: Add the immutable public result model**

Define `SessionJournalReader.readAfter(Path, Optional<EventId>)`, where an empty cursor is the initial read and
never a retention gap. Make `JournalReadResult` defensively copy the record list and contain records, optional
first/last IDs, optional `JournalCursorGap`, `ignoredIncompleteTail`, and optional `JournalReadIssue`. Validate
that first and last are either both present or both absent and ordered unsigned.

Use a sealed `JournalReadIssue` hierarchy for I/O, layout, decompression, CBOR/record, event-order, and limit
failures. Each issue carries a bounded diagnostic and the segment path when one is known; do not expose raw
journal payload bytes in diagnostics.

**Step 4: Implement bounded raw streaming**

List recognized segments, open the only raw segment, feed fixed-size buffers into a new `SessionEventDecoder`,
and collect decoded outcomes. Treat any rejected complete record or terminal structural decoder issue as the
terminal journal issue after the valid prefix. Compare IDs through `EventId.compareTo`, reject zero and
non-increasing values, and retain only records greater than the cursor while still tracking the range.

**Step 5: Run the focused test and verify it passes**

Run the command from Step 2. Expected: PASS.

**Step 6: Commit the first slice**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/journal
git add agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java
git commit -m "Read raw AgentD journal segments"
```

### Task 2: Add ordered segments, cursors, ranges, and retention gaps

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Step 1: Write failing segment and cursor tests**

Cover an empty directory, three rotated raw segments, cursors before the first ID, on an ID, between IDs, and
after the tail. Assert that a cursor before a retained first ID returns:

```java
assertThat(result.gap()).contains(new JournalCursorGap(new EventId(5), new EventId(10)));
assertThat(result.records()).extracting(SessionEventRecord::eventId)
        .containsExactly(new EventId(10), new EventId(20));
```

Add event IDs around `Long.MAX_VALUE` and unsigned `u64` maximum and prove selection and monotonic validation use
unsigned order. Add failures for a segment-number hole, zero event ID, duplicate ID, and descending IDs across a
rotation boundary.

**Step 2: Run the focused test and verify the new cases fail**

Run the Task 1 command. Expected: FAIL in segment selection, gap, and unsigned-order cases.

**Step 3: Implement segment snapshots and seek selection**

Recognize only positive eight-digit `.cbor` and `.cbor.zst` names, group candidates by numeric number, sort them,
and reject holes. Decode the first record of each segment, derive the retained floor, choose the last segment
whose first ID is at most the cursor, and scan through the newest segment. Keep the full journal first/last range
even when no records follow the cursor.

**Step 4: Run the focused test and verify it passes**

Run the Task 1 command. Expected: PASS.

**Step 5: Commit the cursor slice**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/journal
git add agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java
git commit -m "Add AgentD journal cursor ranges"
```

### Task 3: Read compressed segments and compression overlap

**Files:**
- Modify: `pom.xml`
- Modify: `agentd/pom.xml`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Step 1: Write failing compressed-segment tests**

Create a Zstandard-compressed closed segment followed by a raw active segment and assert cursor reading crosses
the boundary with exact original encoded records. Publish both raw and compressed copies for one number with
different contents and prove the raw copy wins. Add a damaged Zstandard stream and assert a decompression issue
is returned after records from earlier segments.

**Step 2: Run the focused test and verify it fails**

Run the Task 1 command. Expected: FAIL because `.cbor.zst` cannot yet be opened.

**Step 3: Add the Zstandard dependency**

Declare one root `zstd-jni` version property and add `com.github.luben:zstd-jni` to `agentd`. Keep compression
details private to the filesystem reader; no Zstandard type appears in the public API.

**Step 4: Implement compressed streaming and raw overlap preference**

Wrap closed compressed files in `ZstdInputStream`, enforce a 512 MiB decompressed-segment bound while streaming,
and close every layer with try-with-resources. Prefer the raw candidate whenever both published names exist.

**Step 5: Run the focused test and verify it passes**

Run the Task 1 command. Expected: PASS.

**Step 6: Commit the compression slice**

```bash
git add pom.xml agentd/pom.xml
git add agentd/src/main/java/pro/deta/orion/agentd/journal
git add agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java
git commit -m "Read compressed AgentD journal segments"
```

### Task 4: Distinguish active crash tails from corruption

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Step 1: Write failing recovery tests**

Cover a complete prefix followed by a partial CBOR item in the newest raw segment and assert the prefix is
returned with `ignoredIncompleteTail == true` and no issue. Put the same partial item in a closed raw segment and
in a compressed segment and assert both produce a terminal CBOR issue. Cover an invalid CBOR marker, a complete
record with missing mandatory fields, and an oversized item; every case must preserve the valid prefix and stop
at the damage.

**Step 2: Run the focused test and verify it fails**

Run the Task 1 command. Expected: FAIL in tail classification and prefix preservation cases.

**Step 3: Implement finish policy and issue mapping**

Call `SessionEventDecoder.finish()` for every closed or compressed stream. For the newest raw segment, inspect
`pendingBytes()` and mark an ignored incomplete tail instead of calling `finish`. Convert rejected outcomes and
terminal issues to `JournalReadIssue`, stop at the first damage, and retain records already validated.

**Step 4: Run the focused test and verify it passes**

Run the Task 1 command. Expected: PASS.

**Step 5: Commit the recovery slice**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/journal
git add agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java
git commit -m "Recover valid AgentD journal prefixes"
```

### Task 5: Verify concurrent append, replacement, retention, and restart reads

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReader.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java`

**Step 1: Add failing live-filesystem tests**

Use a real temporary directory. Append a complete record after an initial read and assert a second read from the
first tail finds it. Replace a closed raw segment with a compressed copy while repeatedly reading and assert every
successful snapshot contains ordered exact records. Delete an oldest segment between reads and assert the next
read returns the new retention gap. Construct a new reader instance and prove it reconstructs the same range and
suffix without persistent state.

**Step 2: Run the focused test and verify any missing behavior fails**

Run the Task 1 command. Expected: retry/restart cases fail until snapshot retry is complete.

**Step 3: Add one bounded stale-snapshot retry**

When opening a discovered path fails with `NoSuchFileException`, discard every partial value from that attempt
and retry discovery once. Return an I/O issue after the second disappearance. Do not retry format corruption,
decompression failure, or general I/O errors.

**Step 4: Run the focused test repeatedly**

Run outside the sandbox:

```bash
for iteration in {1..20}; do make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest' || exit 1; done
```

Expected: all 20 iterations PASS without intermittent failures.

**Step 5: Commit the concurrency slice**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/journal
git add agentd/src/test/java/pro/deta/orion/agentd/journal/FileSystemSessionJournalReaderTest.java
git commit -m "Handle concurrent AgentD journal maintenance"
```

### Task 6: Verify the task and prepare dedicated-worktree completion

**Files:**
- Modify: `docs/plans/current-work/agentd/TASK.md`
- Delete: `docs/plans/current-work/agentd/journal-reader/TASK.md`

**Step 1: Run focused verification**

Run outside the sandbox:

```bash
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest'
```

Expected: PASS.

**Step 2: Run routine development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 3: Review the complete diff**

Run `git diff main...HEAD`, `git diff --check`, and inspect the changed APIs for exact-byte ownership, unsigned
comparisons, bounded allocation, path diagnostics, and the design's corruption policy.

**Step 4: Request code review**

Use `superpowers:requesting-code-review`, read `docs/reviews/RULES.md`, and resolve every blocking finding. Repeat
the focused test after each code change and rerun `mvn verify -Pdev -T 4` after the last review fix.

**Step 5: Complete the task tree**

Remove the completed journal-reader leaf directory and remove its link from the AgentD parent task. Keep the
design and implementation plan as historical documentation.

**Step 6: Squash and transfer to main**

Follow the dedicated-worktree rules in `AGENTS.md` and `superpowers:finishing-a-development-branch`: squash every
task-branch commit into exactly:

```text
Implement AgentD session journal reader [task: agentd/journal-reader]
```

Cherry-pick that commit to `main`, run `make test` there, then remove the completed worktree and delete its branch.
Do not report completion until `git worktree list` no longer contains this worktree.

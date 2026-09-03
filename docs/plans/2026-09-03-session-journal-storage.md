# Durable Session Journal Storage Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a central-server module that stores one byte-preserving, durable CBOR Sequence journal per
session and exposes only filesystem-confirmed cursors.

**Architecture:** A top-level `agent-session-server` module owns a synchronous storage contract and a
filesystem implementation. Each session has independent append coordination, a rebuildable segment catalog,
and an uncompressed active segment; closed segments are compressed by bounded background maintenance without
participating in acknowledgement latency.

**Tech Stack:** Java 21, Maven, NIO `FileChannel`, `agent-protocol`, Apache Commons Compress, zstd-jni,
JUnit 5, AssertJ

---

### Task 1: Scaffold the server module and storage contract

**Files:**

- Modify: `pom.xml`
- Modify: `bom/pom.xml`
- Create: `agent-session-server/pom.xml`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/package-info.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournalStorage.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/JournalAppendResult.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/JournalReadResult.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/JournalGap.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/JournalStorageException.java`
- Create: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/JournalStorageContractTest.java`

**Step 1: Add the Maven module skeleton**

Add `<module>agent-session-server</module>` after `agent-protocol` in the root reactor. Add
`pro.deta.orion:agent-session-server:${project.version}` to `bom/pom.xml` and create a jar module whose direct
production dependency is `agent-protocol`; add JUnit and AssertJ as test dependencies.

**Step 2: Write the failing contract tests**

Specify defensive immutable results and argument validation:

```java
@Test
void appendResultOwnsItsNewlyStoredRecords() {
    List<SessionEventRecord> source = new ArrayList<>(List.of(event(1)));
    JournalAppendResult result = new JournalAppendResult(Optional.of(new EventId(1)), source);

    source.clear();

    assertThat(result.newlyStored()).extracting(SessionEventRecord::eventId)
            .containsExactly(new EventId(1));
}

@Test
void gapRequiresRequestedCursorBeforeFirstAvailableEvent() {
    assertThatIllegalArgumentException().isThrownBy(
            () -> new JournalGap(new EventId(5), new EventId(5)));
}
```

Also require `JournalReadResult` to reject a gap inconsistent with its first record and require all result
lists to reject null elements.

**Step 3: Run the contract test and confirm it fails**

Run outside the sandbox:

```shell
make run-test MODULE=agent-session-server TEST='JournalStorageContractTest'
```

Expected: FAIL because the storage contract types do not exist.

**Step 4: Add the minimal public contract**

Use these public shapes:

```java
public interface SessionJournalStorage extends AutoCloseable {
    Optional<EventId> firstEventId(SessionId sessionId) throws JournalStorageException;
    Optional<EventId> lastEventId(SessionId sessionId) throws JournalStorageException;
    JournalAppendResult append(SessionId sessionId, List<SessionEventRecord> records)
            throws JournalStorageException;
    JournalReadResult readAfter(SessionId sessionId, Optional<EventId> cursor)
            throws JournalStorageException;
    @Override
    void close();
}

public record JournalAppendResult(
        Optional<EventId> durableThrough,
        List<SessionEventRecord> newlyStored
) { }

public record JournalReadResult(
        List<SessionEventRecord> records,
        Optional<JournalGap> gap
) { }

public record JournalGap(EventId requested, EventId firstAvailable) { }
```

`JournalStorageException` is checked and carries one of `INVALID_APPEND`, `CONFLICTING_DUPLICATE`,
`STORED_CORRUPTION`, `IO_FAILURE`, or `CLOSED`. Keep filesystem paths and implementation types out of the
public interface.

**Step 5: Run the contract test**

Run the Task 1 focused command.

Expected: PASS.

**Step 6: Commit the module contract**

```shell
git add pom.xml bom/pom.xml agent-session-server
git commit -m "Define session journal storage contracts"
```

### Task 2: Rebuild an uncompressed session journal from disk

**Files:**

- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/JournalStorageConfig.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentCatalog.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorage.java`
- Create: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/JournalTestRecords.java`
- Create: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorageTest.java`

**Step 1: Write recovery tests for empty and existing journals**

Create a temporary root, encode records with `SessionEventCodec`, and write their `encodedRecord()` bytes to
`<root>/session-1/00000001.cbor`. Assert:

```java
try (var storage = new FileSystemSessionJournalStorage(root, testConfig())) {
    assertThat(storage.firstEventId(new SessionId("missing"))).isEmpty();
    assertThat(storage.lastEventId(new SessionId("missing"))).isEmpty();
    assertThat(storage.firstEventId(SESSION)).contains(new EventId(1));
    assertThat(storage.lastEventId(SESSION)).contains(new EventId(3));
}
```

Add records with an unknown event type and a future trailing field. Require `readAfter(..., Optional.empty())`
to return byte-identical `encodedRecord()` values.

**Step 2: Add invalid-layout tests**

Require a stored-corruption failure for duplicate segment numbers, a numeric gap after the first available
segment, decreasing event IDs across segment boundaries, and an incomplete non-final segment. Allow the first
existing segment number to be greater than one so future prefix retention remains representable.

**Step 3: Run the focused tests and confirm they fail**

```shell
make run-test MODULE=agent-session-server \
  TEST='FileSystemSessionJournalStorageTest#opensEmptyAndExistingJournals+preservesOpaqueRecordsOnRead+rejectsInvalidStoredLayouts'
```

Expected: FAIL because the filesystem implementation is absent.

**Step 4: Implement lazy per-session catalog reconstruction**

`FileSystemSessionJournalStorage` owns a concurrent map from `SessionId` to `SessionJournal`. Open a
`SessionJournal` lazily on its first operation. `SegmentReader` must:

- accept only eight-digit `.cbor` and `.cbor.zst` segment names plus recognized temporary maintenance files;
- order segments by their unsigned numeric name;
- incrementally decode records using `SessionEventDecoder` and configured `AgentProtocolLimits`;
- retain each segment's number, first ID, last ID, representation, physical path, and complete byte length;
- reject structural damage and event-ID regression;
- preserve `SessionEventRecord.encodedRecord()` rather than decode known payload semantics.

Use a package-private immutable `SegmentCatalog` rebuilt entirely from segment contents. Do not create a
cursor or range metadata file.

**Step 5: Implement stable uncompressed reads**

Resolve a snapshot under the session lock and decode it after releasing the lock. `readAfter` skips records
whose unsigned event ID is less than or equal to the cursor. If the cursor predates the first available ID,
return a `JournalGap(cursor, firstAvailable)` together with records from the first available event.

**Step 6: Run the focused tests**

Run the Task 2 command.

Expected: PASS.

**Step 7: Commit disk reconstruction and reads**

```shell
git add agent-session-server
git commit -m "Rebuild session journals from segment files"
```

### Task 3: Append batches behind a filesystem durability barrier

**Files:**

- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/DurableFileOperations.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorage.java`
- Modify: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorageTest.java`
- Create: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/JournalDurabilityTest.java`

**Step 1: Write append and restart tests**

Cover the first batch, a later batch, an empty batch, and a fresh storage instance opened over the same root.
After append, assert that the segment contents are exactly the concatenation of the input encoded records and
that `durableThrough` and `lastEventId` equal the last stored event.

**Step 2: Write a force-order test**

Inject package-private `DurableFileOperations` that records `write`, `force-file`, `force-directory`, and
`publish-catalog`. Require the append result and observable last ID to become available only after all required
force operations. Make `force-file` fail and assert:

```java
assertThatExceptionOfType(JournalStorageException.class)
        .isThrownBy(() -> storage.append(SESSION, List.of(event(1))))
        .extracting(JournalStorageException::reason)
        .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
assertThat(storage.lastEventId(OTHER_SESSION)).isEmpty();
```

The failed session must be poisoned, while another session remains usable.

**Step 3: Run the focused tests and confirm they fail**

```shell
make run-test MODULE=agent-session-server \
  TEST='FileSystemSessionJournalStorageTest#appendsBatchesAndRecoversThemAfterRestart,JournalDurabilityTest'
```

Expected: FAIL because append and durability operations are not implemented.

**Step 4: Implement the NIO durability adapter**

`DurableFileOperations` centralizes directory creation, append channels, `FileChannel.force(true)`, directory
forcing, truncation, atomic move, and deletion. The production implementation must not silently convert a
failed durability operation into success. Keep the injection constructor package-private and the normal
filesystem constructor public.

**Step 5: Implement durable append**

Under the session lock:

1. Validate and classify the entire batch before writing.
2. Create the session directory and initial segment when necessary.
3. Append the exact `encodedRecord().toByteArray()` values.
4. Force each changed segment and any newly created directory entry.
5. Replace the immutable catalog only after the barriers succeed.
6. Return the new durable high-water mark and only newly stored records.

On write or force failure, mark that `SessionJournal` poisoned and reject later operations until a new storage
instance reconstructs it. Do not publish an in-memory cursor from an uncertain append.

**Step 6: Run the focused tests**

Run the Task 3 command.

Expected: PASS.

**Step 7: Commit durable append**

```shell
git add agent-session-server
git commit -m "Persist session journal batches durably"
```

### Task 4: Make retry overlap idempotent and conflict-safe

**Files:**

- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentCatalog.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java`
- Modify: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorageTest.java`

**Step 1: Write retry tests**

Persist events 1 through 3, then append the batch 2 through 5. Require 2 and 3 to be compared and skipped,
only 4 and 5 to appear in `newlyStored`, and the segment bytes to contain each record once. Repeat the same
batch after reopening the storage.

Add a conflicting event 2 with different payload bytes and require `CONFLICTING_DUPLICATE` without any file or
cursor change. Add an internally unordered batch and require `INVALID_APPEND` before any write.

**Step 2: Add unsigned ordering tests**

Use event IDs around the signed boundary (`Long.MAX_VALUE`, `Long.MIN_VALUE`, and unsigned maximum) and require
natural unsigned progression. Confirm that non-consecutive time-derived IDs are valid.

**Step 3: Run the focused tests and confirm they fail**

```shell
make run-test MODULE=agent-session-server \
  TEST='FileSystemSessionJournalStorageTest#skipsIdenticalRetryOverlap+rejectsConflictingRetryWithoutMutation+ordersEventIdsAsUnsignedValues'
```

Expected: FAIL until append classifies overlaps correctly.

**Step 4: Implement retry classification**

Validate strict unsigned order within the request. For IDs at or below the durable high-water mark, locate the
candidate segment by its range, read the stored record, and compare the complete encoded bytes. Do not compare
only decoded payloads or re-encoded values. Once the batch reaches IDs above the high-water mark, require every
remaining ID to remain strictly increasing and append them normally.

**Step 5: Run the focused tests**

Run the Task 4 command.

Expected: PASS.

**Step 6: Commit idempotent retry handling**

```shell
git add agent-session-server
git commit -m "Make journal replication retries idempotent"
```

### Task 5: Rotate segments and recover active tails

**Files:**

- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/JournalStorageConfig.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java`
- Modify: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorageTest.java`

**Step 1: Write rotation tests**

Configure a target smaller than two encoded records. Require rotation before the second record, monotonic
eight-digit segment names, and ordered `readAfter` results across segment boundaries. Add a record larger than
the target and require one complete oversized segment rather than a split item.

**Step 2: Write cursor and retained-prefix tests**

Exercise `readAfter` before the first event, exactly at an event, between non-consecutive IDs, and after the
last event. Delete the oldest closed segment, reopen storage, and require a gap when the cursor predates the new
first available event.

**Step 3: Write active-tail recovery tests**

Append an incomplete copy of the next CBOR record to the highest `.cbor` file and reopen storage. Require the
tail to be truncated to the preceding complete boundary before another append. Put the same tail in a
non-final segment and require `STORED_CORRUPTION`. Also corrupt bytes inside a complete active item and require
corruption rather than tail recovery.

**Step 4: Run the focused tests and confirm they fail**

```shell
make run-test MODULE=agent-session-server \
  TEST='FileSystemSessionJournalStorageTest#rotatesOnlyBetweenRecords+keepsOneOversizedRecordWhole+readsAfterAcrossSegmentsAndReportsGaps+recoversOnlyAnIncompleteActiveTail'
```

Expected: FAIL until rotation and tail truncation exist.

**Step 5: Implement rotation and recovery**

Before each new record, rotate only when the active segment is non-empty and adding the record would exceed the
target. Force the old segment before creating the next one. When reconstructing the highest `.cbor`, use the
decoder's pending-byte count to identify an incomplete trailing item, truncate to the last complete boundary,
force the file, and force its directory before accepting append operations.

Reject incomplete or corrupt closed segments and numeric gaps after the first existing segment. Keep all range
and cursor values derived from complete decoded records.

**Step 6: Run the focused tests**

Run the Task 5 command.

Expected: PASS.

**Step 7: Commit rotation and recovery**

```shell
git add agent-session-server
git commit -m "Rotate and recover session journal segments"
```

### Task 6: Compress closed segments without delaying acknowledgement

**Files:**

- Modify: `pom.xml`
- Modify: `agent-session-server/pom.xml`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentCompressor.java`
- Create: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/JournalMaintenance.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorage.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java`
- Create: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/SegmentCompressionTest.java`

**Step 1: Add Zstandard dependencies**

Manage a `zstd-jni` version in the root POM. Add `commons-compress` and `zstd-jni` to the server module, using
streaming `ZstdCompressorInputStream` and `ZstdCompressorOutputStream` rather than whole-segment byte arrays.

**Step 2: Write compression and transparent-read tests**

Use a direct executor in tests. Rotate a segment, run maintenance, and require the closed `.cbor.zst`, the
active `.cbor`, and byte-identical logical records returned across both. Include incompressible payload bytes.

**Step 3: Write replacement recovery tests**

Inject failures before temporary-file force, before atomic publication, and before original deletion. Require
the original `.cbor` to remain readable. Simulate both representations after a crash:

- valid `.zst` plus valid `.cbor`: prefer `.zst` and schedule cleanup;
- invalid `.zst` plus valid `.cbor`: retain `.cbor` and discard or replace `.zst` through maintenance;
- invalid sole `.zst`: report `STORED_CORRUPTION`.

**Step 4: Prove compression is outside append acknowledgement**

Use a blocking maintenance executor. Rotate and append another batch while compression remains blocked.
Require append to return the forced durable ID before releasing the compressor.

**Step 5: Run the focused tests and confirm they fail**

```shell
make run-test MODULE=agent-session-server TEST='SegmentCompressionTest'
```

Expected: FAIL because compressed representations and maintenance do not exist.

**Step 6: Implement crash-safe background maintenance**

`JournalMaintenance` owns a bounded queue and coalesces pending work by session. A queue overflow leaves the
closed `.cbor` untouched and records a maintenance failure; it never blocks append. `SegmentCompressor` streams
to a sibling temporary file, finishes and forces it, reads it back to verify the logical record range, atomically
publishes `.cbor.zst`, forces the directory, updates the catalog, deletes `.cbor`, and forces the directory
again.

If atomic move is unsupported or any pre-publication step fails, keep `.cbor` as the catalog representation.
On close, stop accepting maintenance, cancel work that has not published, and keep every source segment that
has not completed replacement.

**Step 7: Run the focused tests**

Run the Task 6 command.

Expected: PASS.

**Step 8: Commit compression maintenance**

```shell
git add pom.xml agent-session-server
git commit -m "Compress closed journal segments safely"
```

### Task 7: Verify concurrency and session failure isolation

**Files:**

- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorage.java`
- Modify: `agent-session-server/src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java`
- Create: `agent-session-server/src/test/java/pro/deta/orion/agent/server/journal/JournalConcurrencyTest.java`

**Step 1: Write independent-session concurrency tests**

Block the force operation for session A, append to session B, and require B to complete before A is released.
Also start two appends to one session and require their file writes and cursor publication to remain serialized.

**Step 2: Write snapshot-read tests**

Pause a reader after it captures the catalog snapshot, append another event, and resume the reader. Require the
first result to stop at the old durable boundary and the next `readAfter` call to return the new event. Repeat
while maintenance replaces a closed segment.

**Step 3: Write failure-isolation tests**

Poison session A through an injected force failure. Require reads and appends for session B to remain healthy,
and require reopening storage to reconstruct whatever complete durable prefix exists for A.

**Step 4: Run the focused tests and confirm they fail**

```shell
make run-test MODULE=agent-session-server TEST='JournalConcurrencyTest'
```

Expected: FAIL until locking, snapshots, and failure isolation satisfy the contract.

**Step 5: Complete per-session coordination**

Keep all mutable writer/catalog state inside `SessionJournal`; the top-level concurrent map coordinates only
lazy handle creation. Snapshot segment descriptors and the active durable byte boundary under the session lock,
then release it before decoding. Retry path resolution if maintenance replaces a file before it is opened.

**Step 6: Run all module tests**

```shell
make run-test MODULE=agent-session-server TEST='*Test'
```

Expected: PASS.

**Step 7: Commit concurrency coverage**

```shell
git add agent-session-server
git commit -m "Isolate concurrent session journal operations"
```

### Task 8: Review and verify the complete implementation

**Files:**

- Modify as required by review findings: `agent-session-server/**`
- Modify if dependency wiring changed: `pom.xml`
- Modify if task wording needs correction: `docs/plans/current-work/agent-session-server/journal-storage/TASK.md`

**Step 1: Run module verification**

Run outside the sandbox:

```shell
mvn verify -Pdev -T 4 -pl agent-session-server -am
```

Expected: PASS.

**Step 2: Review against repository rules**

Invoke `superpowers:requesting-code-review`. Read `docs/reviews/RULES.md`, inspect all changes unique to the
task branch, and fix every blocking issue. In particular, confirm that no code path publishes a cursor before
the force barrier, no record is re-encoded, and one session's failure cannot stop another session.

**Step 3: Re-run focused and development verification after fixes**

```shell
make run-test MODULE=agent-session-server TEST='*Test'
mvn verify -Pdev -T 4
```

Expected: PASS.

**Step 4: Commit review fixes if needed**

Use one concise logical message for each distinct fix. Do not mix unrelated working-tree changes.

**Step 5: Finish the dedicated worktree task**

Invoke `superpowers:verification-before-completion` and `superpowers:finishing-a-development-branch`. Follow
`AGENTS.md`: squash task-branch commits into
`Implement durable session journal storage [task: agent-session-server/journal-storage]`, delete the completed
leaf task directory and its parent link in that squashed commit, cherry-pick the result to `main`, run
post-commit `make test` on `main`, and remove the task worktree and branch only after a clean transfer.

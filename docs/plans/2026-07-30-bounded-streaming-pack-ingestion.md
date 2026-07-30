# Bounded Streaming Pack Ingestion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stream receive-pack bytes directly into a repository-owned incremental decoder and hand off a checked quarantine without ever accumulating the complete pack.

**Architecture:** `NativeGitRepository` creates a closeable `PackIngestionSession` backed by a stateful `PackIngestor`. The ingestor consumes caller-owned `ByteBuf` fragments, incrementally parses and inflates objects into a private loose-object quarantine, maintains the pack digest, and completes only after the trailer checksum. A receive-pack continuation owns the session and forwards raw wire fragments until it receives the quarantine.

**Tech Stack:** Java 21, Netty `ByteBuf`, `java.util.zip.Inflater`, SHA-1 `MessageDigest`, continuation runtime, Maven, JUnit 5, AssertJ

---

### Task 1: Define the repository-owned streaming ingestion contract

**Files:**
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackIngestionLimits.java`
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackIngestionResult.java`
- Create: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackIngestionSession.java`
- Modify: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Modify: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java`

**Step 1: Write the failing repository contract tests**

Add tests proving that `beginPackIngestion(limits)` returns independent sessions,
rejects null limits, uses the repository object store for thin delta bases, and
does not expose quarantine before completion.

**Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-native-storage -am \
  -Dtest=NativeGitRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the ingestion types and repository method do
not exist.

**Step 3: Add the minimal public contract**

Use immutable positive limits:

```java
public record PackIngestionLimits(
        long maxPackBytes,
        int maxObjectCount,
        int maxInflatedObjectBytes) {
}
```

Use a sealed result:

```java
public sealed interface PackIngestionResult {
    record NeedInput() implements PackIngestionResult {}
    record Complete(LooseObjectStore quarantine)
            implements PackIngestionResult {}
    record Failed(PackParseException failure)
            implements PackIngestionResult {}
}
```

The session contract is:

```java
public interface PackIngestionSession extends AutoCloseable {
    PackIngestionResult accept(ByteBuf input);
    PackIngestionResult endOfInput();
    @Override
    void close();
}
```

Add `NativeGitRepository.beginPackIngestion(PackIngestionLimits)` and construct
the implementation with the repository's `LooseObjectStore` as the base store.
Do not modify or replace concurrent fetch-related edits already present in this
class.

**Step 4: Run the repository test GREEN**

Run the command from Step 2. Expected: PASS.

**Step 5: Commit the contract**

```bash
git add core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack \
  core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java \
  core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java
git commit -m "Add repository pack ingestion sessions"
```

### Task 2: Replace whole-pack parsing with an incremental state machine

**Files:**
- Modify: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackIngestor.java`
- Modify: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack/PackParseException.java`
- Create: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack/PackIngestionSessionTest.java`
- Modify: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack/PackIngestorTest.java`

**Step 1: Write failing fragmentation and ownership tests**

Build one canonical pack and feed it:

- one byte at a time;
- split within the fixed header, variable object header, deflate stream, and
  trailer;
- as one fragment containing multiple objects.

Assert every call advances the supplied reader index, leaves `refCnt`
unchanged, returns `NeedInput` until the final checksum byte, and transfers a
quarantine containing all expected object IDs exactly once.

**Step 2: Run the session test and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-native-storage -am \
  -Dtest=PackIngestionSessionTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `PackIngestor` is not incremental.

**Step 3: Implement incremental fixed-header and object-header parsing**

Make `PackIngestor` implement `PackIngestionSession`. Store explicit phases:
`PACK_HEADER`, `OBJECT_HEADER`, `DELTA_BASE`, `OBJECT_DATA`, `TRAILER`,
`COMPLETE`, and `FAILED`.

Read directly from the supplied buffer. Update total byte count and the pack
digest as each pre-trailer byte is consumed. Keep only fixed arrays for the
12-byte pack header, 20-byte ref-delta base ID, and 20-byte trailer.

Validate version, object count, type IDs, variable-length size overflow, offset
delta distance, and all configured limits before allocating object content.

**Step 4: Implement fragment-safe inflation**

Keep one `Inflater` for the active object and one small scratch input array.
Copy at most the scratch-array capacity from the current fragment, call
`Inflater.setInput`, inflate into the current object's bounded output array, and
use `Inflater.getRemaining()` to avoid consuming bytes belonging to the next
object. Update the digest and reader index only for compressed bytes actually
consumed by the inflater.

When `Inflater.finished()`:

- require inflated length to equal the declared size;
- resolve delta content when applicable;
- write the resulting object immediately to quarantine;
- remember only its pack offset and object ID/type for later delta lookup;
- reset object-local buffers and move to the next object or trailer.

**Step 5: Implement trailer completion and terminal behavior**

Read exactly 20 trailer bytes without updating the digest. Compare them with the
digest, return `Complete(quarantine)` once, and reject readable bytes after the
trailer. `endOfInput()` returns an incomplete failure unless already complete.
`close()` ends the inflater, drops object-local state, and prevents quarantine
handoff.

Give `PackParseException` a `Kind` of `INCOMPLETE`, `MALFORMED`, or
`LIMIT_EXCEEDED`. Convert expected parse failures into `PackIngestionResult.Failed`.

**Step 6: Add failure and limit tests**

Cover malformed magic/version/type/header/deflate, unavailable delta bases,
checksum mismatch, excess bytes, premature EOF, close, total pack bytes, object
count, inflated object size, quarantine delta bases, and published-store thin
delta bases. Verify failure never returns the quarantine.

Retain the old one-shot `ingest(ByteBuf[, baseStore])` convenience API only as a
compatibility wrapper that creates a session, passes the one supplied buffer,
calls `endOfInput` if necessary, and unwraps the terminal result. It must not
copy the whole pack.

**Step 7: Run all native pack tests GREEN**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-native-storage -am \
  -Dtest=PackIngestorTest,PackIngestionSessionTest,NoDeltaPackBuilderTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 8: Commit the decoder**

```bash
git add core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/pack \
  core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/pack
git commit -m "Stream pack ingestion into quarantine"
```

### Task 3: Connect receive-pack continuation to the repository session

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackBoundaryContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackIngestionContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/LegacyReceivePack.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackContinuationTest.java`
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackIngestionContinuationTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryServiceTest.java`

**Step 1: Implement the production continuation first**

This is a `Continuation` implementation, so follow the repository exception to
test-first TDD: write production continuation logic before its tests.

Add `GitNativeRepositoryService.beginLegacyReceivePack(data, limits)` to resolve
the same repository used for advertisement and open its ingestion session.

Change `ReceivePackBoundaryContinuation` so a command section requiring a pack
transitions to `ReceivePackIngestionContinuation`; a delete-only section keeps a
typed no-pack handoff for later application work.

`ReceivePackIngestionContinuation` owns one session:

```text
accept(input)
  -> NeedInput: transition to this
  -> Complete: completedSuccess(LegacyReceivePack(section, quarantine))
  -> Failed: completedError(sanitized message, cause)
```

It closes the session on continuation close and on failure. It never retains,
releases, or copies the input buffer.

**Step 2: Add continuation and service tests**

Cover original-fragment forwarding, multiple `NeedInput` transitions, completion
only at the checksum, malformed input, close before completion, and reuse of the
repository instance selected from the initial request.

**Step 3: Run focused parser tests GREEN**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=ReceivePackContinuationTest,ReceivePackIngestionContinuationTest,GitNativeRepositoryServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 4: Commit the wire integration**

```bash
git add core/git-parser/src/main core/git-parser/src/test
git commit -m "Ingest receive packs from wire fragments"
```

### Task 4: Verify the slice and update task tracking

**Files:**
- Modify: `TASKS.md`
- Modify if required by implementation: `docs/plans/2026-07-30-bounded-streaming-pack-ingestion-design.md`

**Step 1: Run focused native-storage and parser verification**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-native-storage,core/git-parser -am \
  -Dtest=PackIngestorTest,PackIngestionSessionTest,NativeGitRepositoryTest,ReceivePackContinuationTest,ReceivePackIngestionContinuationTest,GitNativeRepositoryServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 2: Run routine development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 3: Request code review**

Invoke `superpowers:requesting-code-review`, inspect the implementation against
the approved design, and resolve correctness or resource-ownership findings.

**Step 4: Mark the high-level task complete**

Mark the bounded streaming pack-ingestion item complete and remove its owner
line. Preserve the unrelated active task and its owner/status text.

**Step 5: Commit task tracking only**

```bash
git add TASKS.md docs/plans/2026-07-30-bounded-streaming-pack-ingestion.md
git commit -m "Complete bounded streaming pack ingestion"
```

Because this commit changes only Markdown files, do not run tests after it.

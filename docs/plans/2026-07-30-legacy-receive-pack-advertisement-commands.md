# Legacy Receive-Pack Advertisement and Commands Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Send a native protocol v0/v1 receive-pack advertisement and parse a fragmented receive-pack command section into typed exchange values.

**Architecture:** Extend the repository service with receive-pack-specific advertisement construction, then compose advertisement output, pkt-line command headers, exact fragmented payload reads, and a typed pack handoff boundary in the flat continuation graph. The boundary preserves unread raw pack bytes for the later pack-ingestion slice.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion `ContinuationRuntime`, JUnit 5, AssertJ, Maven

---

### Task 1: Add receive-pack exchange values and advertisement

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/LegacyReceiveCommand.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/LegacyReceiveCommandSection.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java`

**Step 1: Add immutable command and command-section records**

Represent old/new `GitObjectId`, ref name, derived create/update/delete type,
client capabilities, initial request, and server advertisement. Defensively
copy mutable collections and reject structurally invalid values.

**Step 2: Add receive-pack advertisement construction**

Add `legacyReceivePackAdvertisement(InitialRequestData)`. Reuse deterministic
ref ordering and the empty-repository pseudo-ref, but advertise only
receive-pack capabilities.

### Task 2: Implement the continuation graph

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceiveCommandContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceiveCommandPayloadContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackBoundaryContinuation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/error/GitWireError.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestDispatchContinuation.java`

**Step 1: Prepare the advertisement during initial dispatch**

Resolve the repository before constructing `ReceivePackContinuation`, matching
the upload-pack error boundary.

**Step 2: Send the advertisement**

Call `GitNativeClientOutput.sendAdvertisement` and use its `transitionTo`
result. Convert unexpected runtime failures to `completedError`.

**Step 3: Parse fragmented command packets**

Use `ControlHeaderContinuation` for pkt-line framing. Accumulate exactly one
payload in `ReceiveCommandPayloadContinuation`, validate it in the owning
command continuation, and loop to the next header.

**Step 4: Add the pack boundary**

On flush, create a `LegacyReceiveCommandSection` and transition to
`ReceivePackBoundaryContinuation`. Complete without reading input so any raw
pack prefix remains available to the future ingestor.

### Task 3: Add continuation tests after production logic

**Files:**
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackContinuationTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryServiceTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestDispatchContinuationTest.java`

**Step 1: Test advertisements**

Cover populated and empty repositories, deterministic refs, and the exact
receive-pack capability set.

**Step 2: Test successful command parsing**

Cover fragmentation at header and payload boundaries, create/update/delete,
first-command capabilities, multiple commands, and preservation of a raw pack
prefix after flush.

**Step 3: Test meaningful failures**

Cover malformed IDs, duplicate refs, late capabilities, empty command lists,
unsupported controls, and output failure.

**Step 4: Run focused verification**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=ReceivePackContinuationTest,GitNativeRepositoryServiceTest,InitialRequestDispatchContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 4: Run development verification and update task tracking

**Files:**
- Modify: `TASKS.md`

**Step 1: Run module verification**

Run:

```bash
mvn verify -Pdev -T 4 -q -pl core/git-parser -am
```

Expected: PASS.

**Step 2: Update task tracking**

Record completion of the legacy receive-pack advertisement and command slice
without disturbing independently active upload-pack work.

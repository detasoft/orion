# Legacy Upload-Pack Request Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Parse the legacy upload-pack want phase through flush into an immutable typed request and hand it to safe negotiation and response placeholders.

**Architecture:** `ControlHeaderContinuation` is the single incremental pkt-line header reader and delegates parsed control state through a stage-specific handler. `UploadRequestContinuation` owns validated wants and first-line capabilities, while `UploadWantPayloadContinuation` reads one payload and returns to the shared header continuation; negotiation and response placeholders fail through continuation flows until those protocol slices are implemented.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion `ContinuationRuntime`, JUnit 5, AssertJ

---

### Task 1: Add the typed legacy upload request

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/LegacyUploadRequest.java`

**Step 1: Define the immutable exchange model**

Create a record containing `InitialRequestData`, ordered immutable sets of
`GitObjectId` wants and String capabilities. Validate all constructor inputs and
copy both sets into unmodifiable `LinkedHashSet` instances.

**Step 2: Check compilation**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am -DskipTests
```

Expected: PASS.

### Task 2: Implement request parsing and placeholder boundaries

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/error/GitWireError.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadRequestContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/ControlPacketHandler.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadWantPayloadContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadNegotiationContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadResponseContinuation.java`

**Step 1: Generalize incremental pkt-line header parsing**

Keep four-byte incremental header parsing in `ControlHeaderContinuation`, add a
whole-header `readInt` fast path, and delegate the parsed `ControlState` to a
`ControlPacketHandler`.

**Step 2: Implement upload request control and payload continuations**

Make `UploadRequestContinuation` own request state and handle DATA/FLUSH
controls. Read one payload in `UploadWantPayloadContinuation`, accept
`want <40-hex-id> [capabilities...]` on the first data packet and only
`want <40-hex-id>` afterward, then transition back to the shared header reader.

**Step 3: Add typed wire errors and the flush transition**

Represent every new upload-request validation with `GitWireError.Kind` and
`GitGeneralException`. Reject flush before a want; otherwise build
`LegacyUploadRequest` and transition to `UploadNegotiationContinuation`.

**Step 4: Add safe placeholders**

Give `UploadNegotiationContinuation` the typed request and a transition factory
for the future `UploadResponseContinuation`. Until negotiation exists,
`process` returns a descriptive terminal error without reading input.
`UploadResponseContinuation` does the same for pack generation.

**Step 5: Check compilation**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am -DskipTests
```

Expected: PASS.

### Task 3: Add continuation tests after production logic

**Files:**
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadRequestContinuationTest.java`
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadNegotiationContinuationTest.java`

**Step 1: Cover successful parsing**

Test one want with first-line capabilities, multiple wants with deduplication,
and a header/payload fragmented across every meaningful boundary. Assert the
resulting transition contains the exact ordered request and does not consume
bytes following flush.

**Step 2: Cover malformed requests**

Test flush before want, malformed object ID, unsupported commands, and
capabilities on later wants. Assert each returns a transition to
`Continuation.CompletedError` rather than throwing.

**Step 3: Cover placeholders**

Assert negotiation and response placeholders leave input unread and transition
to descriptive terminal errors.

**Step 4: Run focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=UploadRequestContinuationTest,UploadNegotiationContinuationTest,UploadPackContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 4: Verify and update task tracking

**Files:**
- Modify: `TASKS.md`

**Step 1: Run module verification**

Run:

```bash
mvn verify -Pdev -T 4 -q -pl core/git-parser -am
```

Expected: PASS.

**Step 2: Check the diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only this implementation plus the pre-existing
probe file are present.

**Step 3: Update the active task**

Keep the high-level Tasks 1–7 item active and update its short status to the next
upload-pack negotiation step. Do not mark the full high-level task complete.

# Legacy Upload Negotiation Implementation Plan

> **For Claude:** Implement locally in the current Orion workspace. Production
> continuation logic precedes tests under the repository's Continuation rule.

**Goal:** Parse multi-round legacy upload-pack haves and done while sending a
placeholder NAK at every intermediate flush.

**Architecture:** An upload negotiation owner keeps ordered deduplicated haves,
uses `ControlHeaderContinuation` for every pkt-line header, and delegates DATA
payloads and round responses to small flat continuations. A typed immutable
negotiation result crosses the final response boundary.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion Continuations, Maven, JUnit 5,
AssertJ

---

### Task 1: Add negotiation exchange state

**Files:**
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/LegacyUploadNegotiation.java`
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadResponseContinuation.java`

**Steps:**
1. Add an immutable record containing the original request and an ordered,
   unmodifiable copy of haves.
2. Change the response boundary to accept the typed negotiation result.
3. Compile the module with skipped tests.

### Task 2: Implement negotiation parsing and round output

**Files:**
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/error/GitWireError.java`
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadNegotiationContinuation.java`
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadNegotiationPayloadContinuation.java`
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadNegotiationResponseContinuation.java`

**Steps:**
1. Add typed errors for invalid negotiation packets, object IDs, commands, and
   controls.
2. Make negotiation transition first to the shared control header reader.
3. Parse one bounded DATA payload and return either to the shared header reader
   after `have` or to the final response after `done`.
4. On flush call `sendNak()` and map Completed/Streaming to transition or
   transition-and-yield back to the same negotiation owner.
5. Compile the module with skipped tests.

### Task 3: Add continuation tests

**Files:**
- Modify:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadNegotiationContinuationTest.java`

**Steps:**
1. Cover one have followed by done.
2. Cover fragmentation and ordered deduplication across two flush-separated
   rounds.
3. Cover Completed and Streaming NAK output paths.
4. Cover malformed object IDs, unsupported commands, empty DATA, delimiter,
   and response-end.
5. Run focused git-parser tests outside the sandbox.

### Task 4: Verify and update tracking

**Files:**
- Modify: `TASKS.md`

**Steps:**
1. Run `mvn verify -Pdev -T 4 -q -pl core/git-parser -am` outside the sandbox.
2. Run `git diff --check` and inspect status.
3. Keep the parent Tasks 1–7 item active and update its next action.

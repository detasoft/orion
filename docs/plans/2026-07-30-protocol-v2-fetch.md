# Protocol V2 Fetch Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement the base protocol v2 server fetch request and stream its native pack response.

**Architecture:** Parse pkt-line arguments with flat continuations into the existing `NativeFetchRequest`. Add a protocol v2 output operation that writes the `packfile` section header, side-band channel-one pack packets, and the final flush while retaining current output backpressure and failure handling.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion Continuations, JUnit 5, AssertJ

---

### Task 1: Correct the v2 capability advertisement

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java`

1. Change the advertised command from `fetch=shallow` to `fetch`.
2. Update the exact-byte advertisement assertion.
3. Run the focused `UploadPackContinuationTest` outside the sandbox.

### Task 2: Parse the base fetch request

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchPayloadContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/ProtocolV2FetchRequest.java`
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuationTest.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/error/GitWireError.java`

1. Implement continuation parsing first, as required by `AGENTS.md` for
   `Continuation` implementations.
2. Accept `want <oid>`, `have <oid>`, `done`, `thin-pack`, `ofs-delta`,
   `no-progress`, and `include-tag`; parse payloads without retaining input
   buffers.
3. On flush, validate at least one want and `done`, then transition to response.
4. Add tests for fragmented input, multiple object ids and flags, invalid ids,
   missing required arguments, unknown arguments, and control packets.
5. Run the focused `FetchContinuationTest` outside the sandbox.

### Task 3: Stream the v2 packfile response

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchResponseContinuation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuationTest.java`

1. Add a completion-aware output response that writes
   `000dpackfile\n`, side-band channel-one pack packets, and `0000`.
2. Add output tests first for exact framing, backpressure, producer close, and
   serialization/delivery failure.
3. Add the service method and response continuation.
4. Test repository request mapping and end-to-end continuation output.
5. Run the focused parser/output tests outside the sandbox.

### Task 4: Track extensions and verify

**Files:**
- Modify: `TASKS.md`

1. Add one future high-level task for shallow, filter, ref-in-want,
   sideband-all, wait-for-done, and packfile URI support.
2. Run:

   `mvn test -Pdev -T 4 -q -pl core/git-parser -am -Dtest=GitNativeClientOutputTest,UploadPackContinuationTest,FetchContinuationTest -Dsurefire.failIfNoSpecifiedTests=false`

3. Run `mvn verify -Pdev -T 4`.
4. Inspect `git diff` and `git status --short`, leaving unrelated changes
   unstaged.

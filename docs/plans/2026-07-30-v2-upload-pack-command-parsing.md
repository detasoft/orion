# Protocol V2 Upload-Pack Command Parsing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Advertise protocol v2 upload-pack capabilities and stream-parse `ls-refs` and `fetch` requests into dedicated continuation placeholders.

**Architecture:** Extend the existing native client output contract with a fixed v2 advertisement, then parse the initial command pkt-line byte by byte. The delimiter dispatches to a command-specific placeholder without consuming its arguments; capability, argument, and response handling remain out of scope.

**Tech Stack:** Java 21, Netty `ByteBuf`, continuation runtime, JUnit 5, AssertJ

---

### Task 1: Add protocol v2 capability output

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuation.java`

**Step 1:** Add `sendV2UploadPackAdvertisement()` using the existing output
serialization contract. Encode four pkt-lines in order (`version 2`,
`ls-refs`, `fetch=shallow`, `server-option`) followed by flush.

**Step 2:** Implement `UploadPackContinuation.process()` so failed output
completes with the returned error, completed output transitions to the v2
request parser, and streaming output transitions and yields.

### Task 2: Add streaming protocol v2 command dispatch

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandPayloadContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuation.java`

**Step 1:** Implement the production continuation graph before tests, following
the repository exception for `Continuation` classes. Reuse
`ControlHeaderContinuation`; consume fragmented payloads in a dedicated
payload continuation.

**Step 2:** Follow the `InitialRequestParser` shape with a nested byte-level
parser. Match only the two fixed command packets without creating a `String`,
`StringBuilder`, or payload-sized byte array.

**Step 3:** Dispatch the delimiter to `LsRefsContinuation` or
`FetchContinuation` without consuming subsequent input. Keep both placeholders
explicit and make processing fail with a command-specific not-implemented
error.

### Task 3: Add continuation and output tests

**Files:**
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java`

**Step 1:** Test the exact advertisement bytes, completed output, and streaming
output.

**Step 2:** Test fragmented headers and payloads for valid `ls-refs` and
`fetch`, asserting the target continuation and that bytes after the delimiter
remain unread.

**Step 3:** Test unknown commands, data before the delimiter, and unsupported
control packets.

**Step 4:** Run focused tests outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeClientOutputTest,UploadPackContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all selected tests pass.

### Task 4: Verify the development build

**Step 1:** Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: build success with no test failures.

**Step 2:** Inspect `git diff` and `git status`, ensuring unrelated untracked
files remain untouched.

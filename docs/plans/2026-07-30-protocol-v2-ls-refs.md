# Protocol V2 `ls-refs` Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the protocol v2 `ls-refs` error placeholder with fragment-safe request parsing and backpressured native ref responses.

**Architecture:** A flat continuation graph parses typed `ls-refs` arguments, `GitNativeRepositoryService` turns a repository snapshot into deterministic response rows, and `GitNativeClientOutput` serializes those rows plus flush through `SendResult`. The implementation keeps repository policy and wire output out of the continuation while preserving the existing completed/streaming/failure transitions.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion Continuations, JUnit 5, AssertJ, Maven.

---

### Task 1: Add typed `ls-refs` request and response values

**Files:**

- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/exchange/LsRefsRequest.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/advertisement/GitLsRefsResponse.java`

**Step 1: Add the request value**

Create an immutable request containing `peel`, `symrefs`, `unborn`, and an
ordered immutable list of ref prefixes. Its matching method returns true when
the prefix list is empty or any prefix matches the candidate ref name. Use an
ordinary loop so overlapping prefixes cannot duplicate response rows.

**Step 2: Add the response value**

Create a response record with an immutable list of rows. Model rows as:

```java
public sealed interface Ref
        permits DirectRef, UnbornRef {
    String name();
}

public record DirectRef(
        String objectId,
        String name,
        Optional<String> symrefTarget,
        Optional<String> peeledObjectId) implements Ref {
}

public record UnbornRef(
        String name,
        String symrefTarget) implements Ref {
}
```

Validate non-null fields and copy lists at construction.

### Task 2: Resolve deterministic repository responses

**Files:**

- Modify: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryServiceTest.java`

**Step 1: Expose object lookup required for tag peeling**

Add a production `readObject(GitObjectId)` method to `NativeGitRepository`
delegating to `LooseObjectStore.read`. This is repository functionality, not a
test-only helper.

**Step 2: Implement response construction**

Add:

```java
public GitLsRefsResponse lsRefs(
        InitialRequestData data,
        LsRefsRequest request)
```

Resolve the repository using the existing path logic, snapshot refs, and build:

- resolved `HEAD` when it matches, adding its target only for `symrefs`;
- unborn `HEAD` when requested, matched, and the default target is absent;
- matching direct refs sorted lexicographically.

Do not add a direct ref twice when several prefixes match.

For requested peeling, inspect tag refs whose object is `ObjectType.TAG`.
Parse the leading ASCII `object <40-hex-id>\n` header, validate it with
`GitObjectId.of`, and follow tag-to-tag targets until a non-tag object is
reached. Detect cycles and missing or malformed targets and omit the optional
peeled attribute rather than failing the entire ref listing.

**Step 3: Add service tests after production code**

Cover:

- sorted branches and lightweight tags;
- overlapping prefixes without duplicates;
- resolved `HEAD` with and without `symref-target`;
- unmatched prefixes producing an empty response;
- unborn `HEAD`;
- an annotated tag peeling to its final non-tag object.

**Step 4: Run the focused service test**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeRepositoryServiceTest
```

Expected: PASS.

### Task 3: Serialize protocol v2 ref responses

**Files:**

- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`

**Step 1: Add response serialization**

Add:

```java
public SendResult sendLsRefs(GitLsRefsResponse response)
```

Convert rows to ASCII payloads:

```text
<object-id> <name> [symref-target:<target>] [peeled:<object-id>]\n
unborn <name> symref-target:<target>\n
```

Reuse `AsciiPacketSequenceSerialization` so serialization terminates with
`0000` and honors the existing bounded buffer/streaming task contract. Catch
validation and serialization exceptions and return `SendResult.Failed` with
the message `Failed to serialize protocol v2 ls-refs response`.

Keep the change separate from the pre-existing side-band edits in this file.

**Step 2: Add output tests after production code**

Cover exact bytes for direct, symref, peeled, unborn, and empty responses.
Exercise both immediate completion and a full initial output buffer that
returns `Streaming`. Add invalid non-ASCII or oversized row coverage and assert
`SendResult.Failed`.

**Step 3: Run the focused output test**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeClientOutputTest
```

Expected: PASS, including the pre-existing side-band tests.

### Task 4: Implement the fragment-safe continuation graph

**Files:**

- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsContinuation.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsArgumentPayloadContinuation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuation.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsContinuationTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java`

**Step 1: Implement production continuation logic first**

Make `LsRefsContinuation` own a request builder and transition to
`ControlHeaderContinuation(this::next)`. Handle:

- DATA with positive payload length by transitioning to
  `LsRefsArgumentPayloadContinuation`;
- FLUSH by building the request, calling repository service, calling
  `sendLsRefs`, and using `SendResult.transitionTo(new
  UploadCommandContinuation(context, data))`;
- DELIMITER and RESPONSE_END as `INVALID_PROTOCOL_V2_REQUEST`;
- empty DATA as invalid.

Catch unexpected repository/runtime failures and return a completed error with
`Failed to serve protocol v2 ls-refs`.

Implement the payload continuation as a bounded byte-by-byte ASCII parser. It
recognizes exact `peel`, `symrefs`, and `unborn` packets and
`ref-prefix <value>` packets ending in LF. Unknown well-formed arguments are
ignored. Reject non-ASCII, missing LF, empty ref prefixes, or extra content on
known flags. Return to the same `LsRefsContinuation` after the declared payload
length is consumed.

Change advertised capability bytes from `ls-refs\n` to
`ls-refs=unborn\n`.

**Step 2: Add continuation tests after production code**

Drive the continuation one byte at a time and cover:

- fragmented flags and repeated prefixes;
- unknown argument tolerance;
- malformed known arguments and invalid control packets;
- exact ordinary, symref, peeled, unborn, and empty output;
- streaming output transition and task completion;
- transition back to `UploadCommandContinuation` without consuming the next
  command bytes;
- repository/output failure propagation.

Update the advertisement assertion to `ls-refs=unborn`.

**Step 3: Run focused continuation tests**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=LsRefsContinuationTest,UploadPackContinuationTest
```

Expected: PASS.

### Task 5: Verify the complete slice

**Files:**

- Modify only if needed to fix failures in files owned by this slice.

**Step 1: Run all git-parser tests**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am
```

Expected: PASS.

**Step 2: Run routine development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 3: Inspect scope**

Run:

```bash
git status --short
git diff --check
git diff --stat
```

Confirm that the pre-existing `TASKS.md`, side-band output/test changes, and
legacy ACK/NAK plan remain unstaged and are reported separately. Do not commit
implementation files unless the user explicitly requests a commit.

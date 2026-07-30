# Streaming Fetch Payload Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Parse protocol v2 fetch pkt-line payloads incrementally without retaining a whole-payload byte array in a continuation.

**Architecture:** Replace `FetchPayloadContinuation`'s byte-array accumulator with a stateful byte-at-a-time parser. The parser returns a typed fetch argument only after consuming the declared payload length; `FetchContinuation` remains responsible for applying arguments to request state.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion `Continuation`, JUnit 5

---

### Task 1: Add incremental fetch argument parsing

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchPayloadContinuation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuation.java`

**Step 1: Define typed fetch arguments**

Add a package-private nested argument type to `FetchContinuation` for `want`,
`have`, `done`, `thin-pack`, `ofs-delta`, `no-progress`, and `include-tag`.
Change `accept` to apply that typed value instead of decoding a complete byte
array.

**Step 2: Implement the payload parser**

Replace `byte[] payload` and `payloadBytes` with a parser holding only:

- remaining pkt-line bytes;
- the current grammar phase;
- indexes for fixed tokens;
- the 40 hexadecimal digits needed to construct a `GitObjectId`.

Consume bytes directly from the supplied `ByteBuf`. Accept one optional final
newline and reject incomplete tokens, invalid ASCII, NUL bytes, trailing bytes,
and malformed object ids.

**Step 3: Complete the continuation transition**

When input ends before the declared payload, return
`ContinuationFlow.await()`. When parsing finishes, pass the typed argument to
`FetchContinuation.accept`, then transition to
`ControlHeaderContinuation`. Convert parser failures to the existing
`FetchContinuation.failed()` transition.

### Task 2: Extend continuation tests

**Files:**
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuationTest.java`

**Step 1: Add meaningful fragmentation coverage**

Drive one valid request through input fragments that split a fixed command
token and split a 40-digit object id. Assert the resulting
`NativeFetchRequest` contains the expected wants, haves, and flags.

**Step 2: Add malformed streaming input coverage**

Add a case for a valid token followed by trailing data, or an incomplete object
id at the declared payload boundary, and assert the existing completed-error
transition.

**Step 3: Run focused tests outside the sandbox**

Run:

`mvn test -Pdev -T 4 -q -pl core/git-parser -am -Dtest=FetchContinuationTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: all `FetchContinuationTest` cases pass.

### Task 3: Verify the change

**Step 1: Inspect retained state**

Run:

`rg -n "byte\\[\\] payload" core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchPayloadContinuation.java`

Expected: no matches.

**Step 2: Run development verification outside the sandbox**

Run:

`mvn verify -Pdev -T 4`

Expected: `BUILD SUCCESS`.

**Step 3: Review scope**

Inspect `git diff` and `git status --short`. Confirm only the fetch payload
parser, its request-state integration, its tests, and these planning documents
belong to this correction; leave all unrelated working-tree changes unstaged.

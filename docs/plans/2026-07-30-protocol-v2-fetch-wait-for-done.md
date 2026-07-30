# Protocol V2 Fetch Wait-For-Done Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow protocol v2 `fetch` requests to negotiate without immediate `done`, ACK repository-owned `have` objects, and send packfile output only after the client sends `done`.

**Architecture:** Keep protocol parsing in `FetchContinuation`, split non-final negotiation output into its own continuation, and keep final packfile output in `FetchResponseContinuation`. Add a small repository-service query that returns the ordered subset of requested `have` objects that exist in the selected native repository; the output layer serializes either `ACK <object>` rows or `NAK` inside an `acknowledgments` section.

**Tech Stack:** Java, Netty `ByteBuf`, Orion Continuation state machines, Maven/JUnit/AssertJ, native Git storage abstractions.

---

## Design

Protocol v2 `fetch` is allowed to run negotiation rounds before the final packfile request. A request with `want` and optional `have` lines but no `done` should not fail. It should return a standalone `acknowledgments` response and leave the connection ready for another protocol v2 command request.

When the client sends `wait-for-done`, the server must not use `ready` to combine a negotiation response with a packfile response. This slice therefore does not emit `ready` at all. It only emits final packfile output when the request contains `done`.

`FetchContinuation` remains responsible for parsing supported fetch arguments. On request flush:

- if `wants` is empty, fail as an invalid v2 fetch request;
- if `done` is true, build `NativeFetchRequest` and transition to `FetchResponseContinuation`;
- if `done` is false, build `NativeFetchRequest` and transition to a new negotiation response continuation.

The new negotiation response continuation asks `GitNativeRepositoryService` for acknowledged haves and writes:

```text
acknowledgments
ACK <have>
ACK <have>
flush
```

If no haves are acknowledged, it writes:

```text
acknowledgments
NAK
flush
```

The repository-service method should preserve the client's `have` order and should not create pack producers or start packfile serialization.

## Implementation Tasks

### Task 1: Fetch parser accepts negotiation requests

**Files:**
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuationTest.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuation.java`

**Step 1: Write the failing test**

Add a test that sends `want <oid>`, `have <oid>`, `wait-for-done`, and `flush` without `done`. Assert the resulting continuation is the new negotiation response continuation and that its request has `done=false`, wants, haves, and wait-for-done state.

**Step 2: Run test to verify it fails**

Run: `mvn test -Pdev -T 4 -q -pl core/git-parser -Dtest=FetchContinuationTest`

Expected: FAIL because `wait-for-done` is currently unsupported and fetch without `done` is invalid.

**Step 3: Write minimal implementation**

Parse `wait-for-done`, keep it in fetch request state, and transition to the negotiation response continuation when `done=false`.

**Step 4: Run test to verify it passes**

Run: `mvn test -Pdev -T 4 -q -pl core/git-parser -Dtest=FetchContinuationTest`

Expected: PASS.

### Task 2: Repository-backed acknowledgments

**Files:**
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryServiceTest.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java`
- Modify as needed: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Modify as needed: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java`

**Step 1: Write the failing test**

Add a service test that creates a native repository with a known object, sends haves containing one present and one missing object, and asserts only the present object is acknowledged in original request order.

**Step 2: Run test to verify it fails**

Run: `mvn test -Pdev -T 4 -q -pl core/git-parser -Dtest=GitNativeRepositoryServiceTest`

Expected: FAIL because no acknowledgment query exists.

**Step 3: Write minimal implementation**

Add a small service method for protocol v2 fetch acknowledgments. If native storage does not expose object-existence checks yet, add the narrowest method needed there and cover it with a focused storage test.

**Step 4: Run test to verify it passes**

Run: `mvn test -Pdev -T 4 -q -pl core/git-parser -Dtest=GitNativeRepositoryServiceTest`

Expected: PASS.

### Task 3: Serialize protocol v2 negotiation responses

**Files:**
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchNegotiationResponseContinuation.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/FetchContinuationTest.java`

**Step 1: Write the failing output tests**

Add tests for:

- `acknowledgments` plus ordered `ACK <oid>` rows plus `flush`;
- `acknowledgments` plus `NAK` plus `flush`;
- output-in-progress failure behavior consistent with existing output operations.

**Step 2: Run test to verify it fails**

Run: `mvn test -Pdev -T 4 -q -pl core/git-parser -Dtest=GitNativeClientOutputTest`

Expected: FAIL because negotiation serialization does not exist.

**Step 3: Write minimal implementation**

Add a typed output operation for protocol v2 fetch acknowledgments and use it from `FetchNegotiationResponseContinuation`.

**Step 4: Run focused tests**

Run: `mvn test -Pdev -T 4 -q -pl core/git-parser -Dtest=GitNativeClientOutputTest,FetchContinuationTest`

Expected: PASS.

### Task 4: Verify final behavior and task state

**Files:**
- Modify: `TASKS.md`

**Step 1: Run module verification**

Run: `mvn verify -Pdev -T 4 -pl core/git-parser,core/git-native-storage`

Expected: PASS.

**Step 2: Update task tracker**

Mark only the wait-for-done negotiation slice complete or update its owner line with the next high-level protocol v2 fetch slice.

**Step 3: Review diff**

Run: `git status --short` and `git diff --stat`.

Expected: only this task's files are modified, plus any unrelated pre-existing files left unstaged.

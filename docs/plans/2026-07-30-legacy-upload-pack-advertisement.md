# Legacy Upload-Pack Advertisement Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Resolve an in-memory native repository and stream its protocol v0/v1 upload-pack advertisement from `v0v1.UploadPackContinuation`.

**Architecture:** Add the native repository provider to the wire context and expose the minimal ref snapshot required by the continuation. `GitNativeClientOutput` owns resumable advertisement serialization and returns either a completed result or one real client-send task that finishes serialization and submits every produced chunk before the continuation runtime resumes.

**Tech Stack:** Java 21, Netty `ByteBuf`, Maven, JUnit 5, AssertJ

---

### Task 1: Expose repository advertisement state

**Files:**
- Modify: `core/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Test: `core/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java`

**Step 1: Add the minimal production API**

Store the constructor arguments and expose immutable repository information:

```java
public String name()
public String defaultHead()
public Map<String, String> refs()
```

`refs()` delegates to `LooseRefStore.snapshot()`.

**Step 2: Write repository tests**

Cover the empty snapshot and a ref update becoming visible through a later
snapshot. Verify the configured default HEAD.

**Step 3: Run the focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-native-storage -am \
  -Dtest=NativeGitRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 2: Add resumable streaming to GitNativeClientOutput

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`

**Step 1: Define the output result and client send port**

Add a sealed result with completed and streaming variants. Supply a client-send
callback to the output. The callback receives each readable chunk produced by
the serializer and owns submission to the client.

Keep the existing constructor as a test/convenience path whose send operation
throws `IllegalStateException("not implemented")` when invoked.

**Step 2: Implement resumable advertisement serialization**

Represent the advertisement as an ordered sequence of encoded pkt-line byte
arrays plus the terminal flush packet. Track the current line and byte offset.
Write until either the sequence completes or the fixed buffer becomes full.

When the buffer fills, retain the source advertisement and cursor and return a
streaming task. The task repeatedly:

1. submits the readable output bytes to the client;
2. clears the fixed buffer;
3. resumes serialization at the saved cursor;
4. submits the final partial buffer after serialization completes.

Clear the retained operation in `finally`. Reject a concurrent output operation
with `IllegalStateException`.

**Step 3: Extend output tests**

Cover:

- the existing single-buffer encoding;
- streaming an advertisement across multiple client sends;
- byte-for-byte equality with the expected pkt-line encoding;
- cleanup after the task completes;
- concurrent-operation rejection;
- propagation of client-send failures.

**Step 4: Run the focused output tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeClientOutputTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 3: Connect the provider to the wire context

**Files:**
- Modify: `core/git-parser/pom.xml`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: context construction in `core/git-parser/src/test/java`

**Step 1: Add the direct module dependency**

Add `git-native-storage` as a direct production dependency of `git-parser`.
Check the reactor graph before finishing; if this introduces a dependency
cycle, move the narrow repository lookup/snapshot port to the lowest existing
shared module and let the provider implement it.

**Step 2: Extend Context construction**

Require `InMemoryNativeGitRepositoryProvider` in the production machine
constructor and expose it from `Context`. Update test contexts with isolated
providers.

**Step 3: Run compilation and existing context tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeClientOutputTest,InitialRequestDispatchContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 4: Implement v0/v1 upload-pack continuation

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadPackContinuation.java`
- Create or modify: the next v0/v1 upload-request continuation under `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadPackContinuationTest.java`

**Step 1: Implement production continuation logic**

This step precedes tests because `AGENTS.md` explicitly exempts
`Continuation` implementations from test-first TDD.

Resolve the repository with `findOrCreate`, build deterministic advertised
refs, and use a fixed legacy upload-pack capability list. Include HEAD first
when its target exists; sort the remaining refs by name. For an empty
repository, advertise the zero object ID under `capabilities^{}`.

Convert output results as follows:

```java
Completed -> transition(nextUploadRequestContinuation)
Streaming(task) -> ContinuationFlow.yield(task)
```

If the next request stage is not implemented, create the explicit continuation
placeholder requested by the user:

```java
throw new IllegalStateException("not implemented");
```

**Step 2: Add continuation tests**

Cover:

- populated repository with HEAD first and sorted refs;
- empty repository pseudo-ref;
- repository creation from the request path;
- propagation of the output streaming task as the Yield task;
- transition after a completed output operation.

**Step 3: Run focused continuation tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=UploadPackContinuationTest,InitialRequestDispatchContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 5: Verify and finish tracking

**Files:**
- Modify: `TASKS.md`

**Step 1: Run development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: `BUILD SUCCESS`.

**Step 2: Check the diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only files belonging to this task are changed.

**Step 3: Finish TASKS.md**

Keep the larger Tasks 1–7 item open, remove the paused owner line for this
completed slice, and record only the next high-level server slice if needed.


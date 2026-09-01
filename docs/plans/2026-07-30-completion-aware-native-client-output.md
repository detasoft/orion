# Completion-Aware Native Client Output Implementation Plan

Status: superseded by
`docs/plans/2026-08-31-blocking-git-native-client-output.md`.

Do not execute this plan. The asynchronous coordinator and its proposed ring
buffer were removed when native Git sessions moved to blocking writes on
virtual threads. This file is retained only as historical design context.

**Goal:** Replace copied native Git output chunks with a completion-aware buffering API, implement double buffering, and leave a tested boundary for a later ring buffer.

**Architecture:** A transport-neutral asynchronous client-write port returns a completion stage for each owned buffer. Typed serializers target a buffer coordinator; the first coordinator alternates two pooled fixed-size buffers and suspends when both are in flight, while a later coordinator implements ordered reclamation over a ring.

**Tech Stack:** Java 21, Netty `ByteBuf`, `CompletionStage`, Maven, JUnit 5, AssertJ

---

### Task 1: Define asynchronous client-write ownership

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientWrite.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`

**Step 1: Write contract tests**

Cover a synchronous submission failure, an exceptional completion, a successful
completion, and immutability until completion.

**Step 2: Run the focused test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeClientOutputTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation failure because `GitNativeClientWrite` is absent.

**Step 3: Add the write port**

Define:

```java
@FunctionalInterface
public interface GitNativeClientWrite {
    CompletionStage<Void> write(ByteBuf ownedBuffer);
}
```

Document that bytes remain immutable until completion and the output
coordinator reclaims the buffer afterward.

**Step 4: Adapt GitNativeClientOutput**

Replace `Consumer<ByteBuf>` with `GitNativeClientWrite`. Preserve the existing
convenience constructor with an explicit
`IllegalStateException("not implemented")` placeholder.

**Step 5: Run the focused test**

Run the command from Step 2. Expected: PASS.

### Task 2: Extract a buffer-coordinator boundary

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/output/GitOutputBufferCoordinator.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/output/OutputBufferLease.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/output/GitOutputBufferCoordinatorContractTest.java`

**Step 1: Define the minimal coordinator contract**

The serializer must be able to:

```java
ByteBuf writableBuffer();
CompletionStage<Void> submitReady();
CompletionStage<Void> awaitWritable();
CompletionStage<Void> finish();
void close();
```

Keep typed Git values and pkt-line rules out of this interface.

**Step 2: Move serialization cursor logic behind the coordinator**

`GitNativeClientOutput` retains the typed value and packet/byte cursor. It
writes only through `writableBuffer` and never handles buffer ownership
directly.

**Step 3: Add reusable contract fixtures**

Create deterministic byte producers that cross boundaries at the first byte,
last byte, exact capacity, and multiple capacities.

**Step 4: Run focused parser tests**

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitOutputBufferCoordinatorContractTest,GitNativeClientOutputTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 3: Implement double buffering

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/output/DoubleGitOutputBufferCoordinator.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/output/DoubleGitOutputBufferCoordinatorTest.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`

**Step 1: Write double-buffer state tests**

Cover:

- serializer and write alternating between two buffers;
- first buffer immutable while the second is writable;
- both buffers in flight causing `awaitWritable` to remain incomplete;
- completion reclaiming exactly the completed buffer;
- write failure preventing reuse until cleanup;
- idempotent close and exact-once release.

**Step 2: Implement explicit buffer states**

Use an enum per slot:

```java
WRITABLE, READY, IN_FLIGHT, CLOSED
```

Keep all state transitions on one serialized coordinator path. Do not mutate
slot state directly from arbitrary completion threads; enqueue completion
results for the coordinator to observe.

**Step 3: Connect the default output construction**

Have the machine composition root allocate two fixed-size buffers and construct
the double coordinator. Keep test factories able to supply a deterministic
coordinator and controllable write stages.

**Step 4: Run double-buffer and output tests**

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=DoubleGitOutputBufferCoordinatorTest,GitNativeClientOutputTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 4: Integrate the real transport completion

**Files:**
- Modify: Netty native Git adapter under `net/git-transport/src/main/java`
- Test: corresponding adapter test under `net/git-transport/src/test/java`
- Modify: `net/git-transport/pom.xml` only if a direct dependency is missing

**Step 1: Write adapter tests**

Use controllable channel futures to cover:

- buffer ownership until write completion;
- channel writability back-pressure;
- ordered execution on the event loop;
- write failure reaching the runtime task failure path;
- channel close releasing both buffer slots.

**Step 2: Implement GitNativeClientWrite over Netty**

Transfer the supplied buffer to `writeAndFlush` and complete the returned stage
from the channel future listener. Do not release a successfully transferred
buffer in the output layer before Netty completes ownership.

**Step 3: Run transport tests**

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest='*Git*Output*,*Git*Adapter*' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 5: Verify double buffering

**Step 1: Run development verification outside the sandbox**

```bash
mvn verify -Pdev -T 4
```

Expected: `BUILD SUCCESS`.

**Step 2: Check the working tree**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and only double-buffer task files changed.

### Task 6: Implement the ring coordinator as a later slice

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/output/RingGitOutputBufferCoordinator.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/output/RingGitOutputBufferCoordinatorTest.java`

**Step 1: Write ring state-model tests**

Cover contiguous ready bytes, wrap-around ready bytes, full ring, two-slice
submission, out-of-order completion, ordered reclamation, failure, and close.

**Step 2: Implement logical positions and in-flight ranges**

Track `reclaim`, `read`, and `write` positions plus an ordered queue:

```java
record InFlightRange(
        int start,
        int length,
        CompletionStage<Void> completion,
        boolean completed) {}
```

Never advance `reclaim` past an incomplete head range.

**Step 3: Reuse coordinator contract tests**

Run the same byte-boundary and ownership contract suite against the ring
implementation.

**Step 4: Run focused ring tests**

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=RingGitOutputBufferCoordinatorTest,GitOutputBufferCoordinatorContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Run full development verification**

```bash
mvn verify -Pdev -T 4
```

Expected: `BUILD SUCCESS`.

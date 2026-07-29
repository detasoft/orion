# Git Minimal Wire Handler Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `GitMinimalWireHandler` correctly bridge Netty `ByteBuf` input to the new `RuntimeFlow` contract, including terminal closure, non-terminal diagnostics, and safe yield ownership.

**Architecture:** Keep the handler as a thin adapter around `GitMinimalWireMachine`. The handler owns one retained input while a yield is pending, schedules all yielded tasks on the channel executor, and maps each runtime outcome to explicit channel behavior without starting a server or adding lifecycle wiring.

**Tech Stack:** Java 21, Netty `EmbeddedChannel`, Orion `ContinuationRuntime`, JUnit 5, AssertJ, Maven

---

### Task 1: Specify terminal and non-terminal runtime outcomes

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/netty/GitMinimalWireHandlerTest.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitMinimalWireHandler.java`

**Step 1: Write failing tests**

Add tests which construct machines from small test continuations and verify:

- `Continuation.completedSuccess(...)` closes the `EmbeddedChannel`;
- `Continuation.completedError(...)` reports its throwable and closes the channel;
- a non-terminal runtime contract error is diagnosed without making the machine
  terminal or closing the channel.

Use an error-recording downstream handler for terminal continuation failures.
For the non-terminal case, assert observable state and channel usability rather
than coupling the test to a logging backend.

**Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest=GitMinimalWireHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation or assertions fail because the handler still uses
`ContinuationFlow` as the runtime result and does not close on `Terminal`.

**Step 3: Implement minimal outcome mapping**

- Change `GitMinimalWireMachine.accept` and `resumeTask` consumers to
  `RuntimeFlow`.
- Expose only the terminal throwable needed by the adapter, without transferring
  input ownership back into the machine.
- On `RuntimeFlow.Terminal`, report a terminal throwable if present, then close
  the machine and channel.
- On `RuntimeFlow.Error`, log a warning and keep the channel and machine open.

**Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: the new outcome tests pass.

### Task 2: Specify yield input ownership and re-entrancy

**Files:**
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/netty/GitMinimalWireHandlerTest.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/netty/GitMinimalWireHandler.java`

**Step 1: Write failing tests**

Extend the test continuation and add cases for:

- one yield and consecutive yields retaining exactly one input reference;
- closing the channel before a scheduled yield runs;
- receiving the same `ByteBuf` object during yield without a second `accept`;
- receiving a distinct `ByteBuf` during yield, releasing that buffer and keeping
  the channel and current machine alive.

**Step 2: Run the focused test and verify RED**

Run the focused Maven command from Task 1.

Expected: at least the close-during-yield or input-during-yield test fails due
to missing explicit pending-input ownership in the handler.

**Step 3: Implement minimal yield pump**

- Store the retained pending input in the handler.
- Make scheduling iterative through the channel executor.
- Reuse the retained input for consecutive yields.
- Release it exactly once on `Await`, `Terminal`, unrecoverable failure, or
  channel closure.
- Ignore a repeated read of the identical pending object.
- For a distinct object, log and release it. Add a detailed TODO explaining
  that a later task must select queueing, cumulation, or deferred sequential
  submission because distinct input during yield is valid.

**Step 4: Run the focused test and verify GREEN**

Run the focused Maven command from Task 1.

Expected: all `GitMinimalWireHandlerTest` tests pass with all asserted
`ByteBuf.refCnt()` values at zero after cleanup.

### Task 3: Verify the adapter in project context

**Files:**
- No production changes expected.

**Step 1: Run focused transport and continuation checks**

Run:

```bash
mvn test -Pdev -T 4 -q \
  -pl core/lifecycle-state-machine,core/git-parser,net/git-transport -am \
  -Dtest=ContinuationRuntimeTest,GitMinimalWireMachineTest,GitMinimalWireHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all selected tests pass.

**Step 2: Run routine development verification**

Run:

```bash
mvn verify -Pdev -T 4
```

Expected: the development build passes. If unrelated pre-existing migration
failures remain, record their exact modules and errors without changing
unrelated work.

**Step 3: Check the diff**

Run `git diff --check` and inspect `git status --short`.

Expected: no whitespace errors; only the handler task and pre-existing user
changes are present.

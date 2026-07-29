# Flat Git Wire Continuations Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace every nested Git wire child runner with direct transitions
between standalone continuation classes.

**Architecture:** One `ContinuationRuntime<ByteBuf>` starts at
`HeaderContinuation` and alone advances the flat graph. Wire parsing state is
shared through a dedicated context, while each continuation owns or explicitly
transfers its local resources.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion `ContinuationRuntime`, Maven

---

### Task 1: Extract shared wire continuation state

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/GitWireContext.java`
- Create auxiliary route, dispatch, semantic, and flow-control types under the
  same package as required.
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`

**Steps:**

1. Move continuation-only mutable state out of the facade.
2. Preserve the public callback and semantic result contracts.
3. Make the facade construct the context and runtime without a child runner.
4. Compile production sources with tests skipped.

### Task 2: Extract the pkt-line chain

**Files:**
- Create: `.../wire/continuation/HeaderContinuation.java`
- Create: `.../wire/continuation/ControlDispatchContinuation.java`
- Create: `.../wire/continuation/PayloadContinuation.java`
- Create: `.../wire/continuation/DispatchContinuation.java`

**Steps:**

1. Start the runtime directly with `HeaderContinuation`.
2. Make header completion transition directly to control dispatch, payload, or
   non-data dispatch.
3. Make control dispatch transition directly to payload.
4. Make payload completion transfer its payload directly to dispatch.
5. Make dispatch route directly to the next standalone continuation or a
   terminal continuation.

### Task 3: Flatten raw and side-band processing

**Files:**
- Create standalone raw and side-band continuation classes under
  `.../wire/continuation/`.

**Steps:**

1. Remove raw and side-band child runners.
2. Pass durable target/decoder state explicitly between direct transitions.
3. Ensure each retained fragment, decoder, and target has one close path.
4. Compile production sources with tests skipped.

### Task 4: Remove the nested implementation

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: `docs/plans/2026-07-28-native-git-in-memory-server.md`

**Steps:**

1. Delete `ChildRunner`, `ChildStep`, and all nested continuation classes.
2. Correct the original Task 0 architecture text so it describes the flat
   runtime-owned graph.
3. Run formatting and whitespace checks.
4. Do not edit existing tests in this architecture pass.

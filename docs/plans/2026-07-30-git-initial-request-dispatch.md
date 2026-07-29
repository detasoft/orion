# Git Initial Request Dispatch Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the temporary structured-payload continuation with service
and protocol-version dispatch that preserves unread input.

**Architecture:** The initial-request payload parser transitions to a small
metadata-only dispatch continuation. The dispatch continuation selects a
legacy v0/v1 upload-pack branch, a v2 upload-pack branch, or a legacy v0/v1
receive-pack branch without reading the supplied `ByteBuf`; unsupported
versions transition to a typed terminal error. Protocol branches are
placeholders in version-specific packages until their conversations are
implemented.

**Tech Stack:** Java 21, Netty `ByteBuf`, Orion
`Continuation<ByteBuf>`, JUnit 5, AssertJ, Maven

---

### Task 1: Specify dispatch behavior with failing tests

**Files:**

- Create:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestDispatchContinuationTest.java`
- Modify:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestPayloadContinuationTest.java`

**Step 1: Write the failing service and version dispatch tests**

Construct `InitialRequestData` directly and process an
`InitialRequestDispatchContinuation`. Cover:

```java
@Test
void dispatchesLegacyUploadPackWithoutVersion() {
    ContinuationFlow<ByteBuf> flow = process(
            dispatch(InitialRequestService.UPLOAD_PACK, Map.of()),
            Unpooled.buffer());

    assertThat(transitionedTo(flow))
            .isInstanceOf(v0v1.UploadPackContinuation.class);
}

@Test
void dispatchesLegacyUploadPackVersionOne() {
    // version=1 -> v0v1.UploadPackContinuation
}

@Test
void dispatchesUploadPackVersionTwo() {
    // version=2 -> v2.UploadPackContinuation
}

@Test
void dispatchesLegacyReceivePackWithoutVersion() {
    // no version -> v0v1.ReceivePackContinuation
}

@Test
void dispatchesLegacyReceivePackVersionOne() {
    // version=1 -> v0v1.ReceivePackContinuation
}
```

Change `InitialRequestPayloadContinuationTest.completedData` into an assertion
that the payload parser transitions to
`InitialRequestDispatchContinuation`. Expose parsed data through a package
private `@TestOnly` accessor because it is not part of the public contract.

**Step 2: Write failure and unread-input tests**

Cover `version=2` for receive-pack, empty version, `version=0`, and an unknown
version. Assert transition to `CompletedError` and a message containing both
the version and service.

Pass a readable buffer to a successful dispatch and assert both its
`readerIndex()` and readable contents are unchanged after `process`.

**Step 3: Run the focused test and verify RED**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=InitialRequestDispatchContinuationTest,InitialRequestPayloadContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: test compilation fails because
`InitialRequestDispatchContinuation` and the version-specific branch classes
do not exist.

### Task 2: Implement the minimal dispatch and placeholders

**Files:**

- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestDispatchContinuation.java`
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/UploadPackContinuation.java`
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackContinuation.java`
- Create:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuation.java`
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestPayloadContinuation.java`
- Delete:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/StructuredPayloadContinuation.java`

**Step 1: Add the protocol branch placeholders**

Each placeholder implements `Continuation<ByteBuf>`, retains the parsed
`InitialRequestData` needed by later work, and contains only:

```java
@Override
public ContinuationFlow<ByteBuf> process(ByteBuf input) {
    throw new IllegalStateException("Not implemented");
}
```

Do not add shared bases before real protocol behavior exists.

**Step 2: Implement dispatch**

`InitialRequestDispatchContinuation` stores the machine context and parsed
request. Its `process` method switches on `InitialRequestService` and the
optional `version` parameter:

```java
return switch (data.getService()) {
    case UPLOAD_PACK -> switch (version) {
        case null, "1" -> transition(new v0v1.UploadPackContinuation(context, data));
        case "2" -> transition(new v2.UploadPackContinuation(context, data));
        default -> unsupported(version, data.getService());
    };
    case RECEIVE_PACK -> switch (version) {
        case null, "1" -> transition(new v0v1.ReceivePackContinuation(context, data));
        default -> unsupported(version, data.getService());
    };
};
```

Use ordinary control flow instead if Java does not permit the shown null switch
form. Do not call any method on `input`. Convert unsupported versions to a
transition to `Continuation.completedError`.

Add a package-private `@TestOnly` accessor for the parsed request.

**Step 3: Connect the payload parser and remove the temporary node**

Transition from `InitialRequestPayloadContinuation` to
`InitialRequestDispatchContinuation`. Delete
`StructuredPayloadContinuation`.

**Step 4: Run the focused tests and verify GREEN**

Run the command from Task 1 Step 3 outside the sandbox.

Expected: PASS.

**Step 5: Check the complete module**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4 -q -pl core/git-parser -am
```

Then run:

```bash
git diff --check
```

Expected: PASS and no whitespace errors.

### Task 3: Finish task tracking and commit

**Files:**

- Modify: `TASKS.md`
- Include: `AGENTS.md`

**Step 1: Update task tracking**

Keep the high-level Tasks 1–7 parent unchecked. Replace the current owner line
with the next concrete server step after dispatch.

**Step 2: Review repository state**

Run:

```bash
git status --short
git diff --stat
git diff --check
```

Confirm that only the requested dispatch work, its plans, task tracking, and
the user-requested `AGENTS.md` documentation rule are included.

**Step 3: Commit the implementation**

Stage only the implementation slice and commit with a one-line message:

```bash
git add AGENTS.md TASKS.md docs/plans/2026-07-30-git-initial-request-dispatch.md \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation
git commit -m "Dispatch Git initial requests by service and protocol version"
```

**Step 4: Run post-commit verification**

Because the commit contains production code, run outside the sandbox:

```bash
make test
```

Expected: `BUILD SUCCESS`.

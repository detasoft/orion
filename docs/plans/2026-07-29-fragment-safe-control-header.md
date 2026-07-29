# Fragment-Safe Control Header Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve fragmented four-byte pkt-line headers across caller-owned
`ByteBuf` chunks and dispatch complete headers without consuming following data.

**Architecture:** `ControlHeaderContinuation` stores only an integer header
value and the number of collected bytes. Each `process` call consumes at most
the bytes still needed for the four-byte header; a complete header transitions
to the stage-appropriate continuation or terminal error while leaving all
following bytes unread.

**Tech Stack:** Java 21, Netty `ByteBuf`, JUnit 5, AssertJ, Maven

---

### Task 1: Cover fragmented and bounded header reads

**Files:**
- Create:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuationTest.java`
- Modify:
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuation.java`

**Step 1: Write failing tests**

Add tests in the style of `InitialRequestPayloadContinuationTest` that process
caller-owned buffers directly and release them in a helper. Cover DATA headers
split after one, two, and three bytes; assert `Await` for the first fragment and
an `InitialRequestPayloadContinuation` transition for the second. Add a complete
header followed by payload or another header and assert the remainder stays
readable. Cover FLUSH, DELIMITER, and RESPONSE_END in `INITIAL_REQUEST` as
stage-specific completed errors.

**Step 2: Verify RED**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=ControlHeaderContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: fragment tests fail because the first fragment is discarded.

**Step 3: Implement the minimum fix**

Add `headerValue` and `headerBytes` fields. Read unsigned bytes one at a time,
left-shifting them into the integer until four bytes are collected. Return
`Await` while incomplete. Decode and dispatch only once complete. Keep DATA
dispatch stage-aware and return descriptive completed errors for control types
that are invalid at the current stage.

**Step 4: Verify GREEN**

Repeat the focused Maven command. Expected: all
`ControlHeaderContinuationTest` cases pass.

**Step 5: Verify the module**

Run:

```bash
mvn verify -Pdev -T 4 -pl core/git-parser -am
git diff --check
```

Expected: Maven exits successfully and the diff has no whitespace errors.

### Task 2: Share ByteBuf continuation test helpers

**Files:**
- Create:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/ByteBufContinuationTest.java`
- Modify:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/ControlHeaderContinuationTest.java`
- Modify:
  `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/InitialRequestPayloadContinuationTest.java`

**Step 1: Extract shared test mechanics**

Create a package-private abstract base class with helpers for the test context,
caller-owned input processing, byte-at-a-time processing with intermediate
`Await` assertions, transition extraction, and completed-error assertions.

**Step 2: Migrate both continuation tests**

Extend the base class from both tests and retain only continuation-specific
construction and assertions in each class.

**Step 3: Verify**

Run both continuation test classes together, then repeat module verification.

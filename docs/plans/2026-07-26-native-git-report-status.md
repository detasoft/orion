# Native Git Report-Status Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add JGit-free receive-pack `report-status` v1 parsing and writing primitives to `core/git-parser`.

**Architecture:** Implement this as a small wire-level parser and writer beside the existing pkt-line and capability helpers. The parser reads pkt-line text packets from `ByteBuf` until flush and returns immutable records; the writer formats caller-provided records through `GitPktLineWriter`. Receive-pack policy, side-band wrapping, reason sanitization, and `report-status-v2` stay outside this change.

**Tech Stack:** Java 21, Maven `-Pdev`, Netty `ByteBuf`, JUnit 5, AssertJ, existing Git pkt-line writer and wire error model.

---

### Task 1: Report-Status Fixtures

**Files:**
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitReportStatusParserWriterTest.java`

**Step 1: Write failing tests**

Add tests for:

- parsing `unpack ok`, two `ok <ref>` lines, and flush;
- parsing `unpack <reason>` plus `ng <ref> <reason>`;
- preserving reject reason text after the ref name;
- writing the exact pkt-line sequence for success and rejection;
- rejecting ref status before unpack with `GitWireError.Kind.MISSING_UNPACK_STATUS`;
- rejecting malformed `ng` lines without a reason with
  `GitWireError.Kind.INVALID_REPORT_STATUS_LINE`.

Use `GitPktLineWriter` in fixtures so the tests exercise real pkt-line framing.

**Step 2: Verify RED**

Run outside the sandbox:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitReportStatusParserWriterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because report-status models, parser, and writer do not exist.

### Task 2: Minimal Report-Status Model

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitReportStatus.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitReportStatusRef.java`

**Step 1: Implement records only**

Add immutable records for:

- unpack status: success or error reason;
- ordered per-ref results;
- per-ref status: `OK` or `NG`;
- ref name;
- optional rejection reason.

Validate model construction enough to prevent impossible writer output, such as
an `NG` ref without a reason or an `OK` ref with a reason.

**Step 2: Run test**

Expected: tests still fail because parser and writer behavior is missing.

### Task 3: Parser Behavior

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitReportStatusParser.java`

**Step 1: Implement parser**

Read packet headers with `GitNativeUtils.packetLength(...)`, parse data payloads
as UTF-8 text, strip one optional trailing LF, and stop at flush.

Recognize:

- `unpack ok`;
- `unpack <reason>`;
- `ok <ref>`;
- `ng <ref> <reason>`.

Validate exactly one unpack status before any ref status. Report semantic grammar
failures through `GitWireException` with typed `GitWireError.Kind` values, not
plain `IllegalArgumentException`. Reject delimiter and response-end packets in
this grammar. Leave bytes after flush unread.

**Step 2: Run focused test**

Run outside the sandbox:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitReportStatusParserWriterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: parser tests pass, writer tests still fail.

### Task 4: Writer Behavior

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitReportStatusWriter.java`

**Step 1: Implement writer**

Write:

1. one unpack line;
2. each ref result line in order;
3. flush.

Return `List<ByteBuf>` like `GitCapabilityWriter.writeProtocolV2Packets(...)`
so callers own packet release.

**Step 2: Verify GREEN**

Run outside the sandbox:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitReportStatusParserWriterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 5: Task Tracking and Module Verification

**Files:**
- Modify: `TASKS.md`

**Step 1: Update task tracking**

Mark `Add report-status parsing and writing for unpack status plus ok/ng per-ref command results.` as done.

**Step 2: Run related tests**

Run outside the sandbox:

```bash
mvn test -Pdev -q -pl core/git-parser -am
```

Expected: PASS.

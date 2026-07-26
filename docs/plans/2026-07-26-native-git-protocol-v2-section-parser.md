# Native Git Protocol V2 Section Parser Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add JGit-free native tests and parser support for protocol v2 command requests in `core/git-parser`.

**Architecture:** Keep this as a small ByteBuf parser below upload-pack and receive-pack policy. The parser reads pkt-line framed protocol v2 requests into wire-level command, capability, argument, delimiter, flush, response-end, and error-packet records without touching repositories, transports, or JGit.

**Tech Stack:** Java 21, Maven `-Pdev`, Netty `ByteBuf`, JUnit 5, AssertJ, existing Git pkt-line writer and wire error model.

---

### Task 1: Protocol V2 Request Fixtures

**Files:**
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitProtocolV2SectionParserTest.java`

**Step 1: Write failing tests**

Add tests built from official Git protocol v2 grammar and upstream Git/JGit scenarios:

- `ls-refs` request: `command=ls-refs`, delimiter, `peel`, `symrefs`, repeated `ref-prefix`, flush.
- `fetch` request: `command=fetch`, optional command capability such as `agent=git/2.42.0`, delimiter, `thin-pack`, `ofs-delta`, `want`, `have`, `done`, flush.
- request with response-end packet after arguments.
- protocol error packet `ERR explanation`.
- invalid request with argument before delimiter.

**Step 2: Verify RED**

Run outside the sandbox:

```bash
mvn test -Pdev -q -pl core/git-parser -Dtest=GitProtocolV2SectionParserTest
```

Expected: compilation fails because `GitProtocolV2SectionParser` and request models do not exist.

### Task 2: Minimal Parser Model

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitProtocolV2Request.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitProtocolV2Line.java`

**Step 1: Implement records only**

Add immutable wire records for:

- command name;
- command capabilities before delimiter;
- argument lines after delimiter;
- terminal packet kind;
- protocol error text when present.

**Step 2: Run test**

Expected: tests still fail because parser behavior is missing.

### Task 3: Minimal Parser Behavior

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitProtocolV2SectionParser.java`

**Step 1: Implement parser**

Read packet headers with `GitNativeUtils.packetLength(...)`, preserve line text with optional trailing LF stripped, recognize:

- data packets;
- flush `0000`;
- delimiter `0001`;
- response-end `0002`;
- error packets beginning with `ERR `.

Validate that the first data packet is `command=<name>`, at most one command exists, arguments only appear after delimiter, and the parser stops at flush, response-end, or error.

**Step 2: Verify GREEN**

Run outside the sandbox:

```bash
mvn test -Pdev -q -pl core/git-parser -Dtest=GitProtocolV2SectionParserTest
```

Expected: PASS.

### Task 4: Focused Module Verification

**Files:**
- Existing parser and test files only.

**Step 1: Run related tests**

Run outside the sandbox:

```bash
mvn test -Pdev -q -pl core/git-parser
```

Expected: PASS.

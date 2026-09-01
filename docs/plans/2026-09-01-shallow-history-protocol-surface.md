# Shallow History Protocol Surface Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add safe protocol v2 shallow-history wire and API surface for
`shallow`, `deepen-since`, `deepen-not`, `deepen-relative`, and `unshallow`
without adding the full Git history walker in this task.

**Architecture:** Extend fetch request and response records so parser,
repository, and transport can carry shallow state explicitly. Keep existing
`deepen <depth>` behavior, reject unsupported deepening forms in the repository
service until storage can compute them correctly, and serialize `unshallow`
lines when response metadata is present.

**Tech Stack:** Java records, JUnit 5, AssertJ, Maven `-Pdev`, Orion git-parser
and git-native-storage modules.

---

### Task 1: Parse Shallow Request Surface

**Files:**
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeFetchRequest.java`

**Steps:**
1. Add a failing session test that sends fragmented fetch request lines with
   `shallow <oid>`, `deepen-relative`, and normal `deepen <depth>`, then asserts
   the request reaches repository code without invalid protocol failure.
2. Run the focused git-parser test outside the sandbox and confirm the new test
   fails because `shallow` or `deepen-relative` is unsupported.
3. Add `clientShallowCommits` and `deepenRelative` fields to `NativeFetchRequest`
   with defensive copies and compatibility constructors.
4. Extend `FetchAccumulator` to parse `shallow <oid>` and `deepen-relative`.
5. Re-run the focused test and keep it green.

### Task 2: Reject Unsupported Deepening Forms Safely

**Files:**
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeFetchRequest.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`

**Steps:**
1. Add failing parser tests for valid `deepen-since <timestamp>` and
   `deepen-not <ref-or-revision>` reaching repository code as structured request
   data.
2. Add failing tests that contradictory or duplicate deepening forms fail
   through `INVALID_PROTOCOL_V2_FETCH_REQUEST`.
3. Extend `NativeFetchRequest` with `deepenSince` and `deepenNotRefs`.
4. Parse and validate the new wire lines in `FetchAccumulator`.
5. Reject repository execution for `deepen-since` and `deepen-not` with a
   standard upload-pack failure until full shallow graph semantics are added.
6. Re-run focused parser and native-storage tests.

### Task 3: Serialize Unshallow Response Metadata

**Files:**
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/ProtocolV2PackfileResponseTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeFetchResponse.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`

**Steps:**
1. Add a failing transport test that a response with both shallow and unshallow
   metadata writes both line types in the `shallow-info` section.
2. Extend `NativeFetchResponse` with `unshallowBoundaries`.
3. Extend `beginProtocolV2Packfile` overloads and response serialization to
   include `unshallow` lines.
4. Pass response unshallow metadata from `GitBlockingWireSession`.
5. Re-run focused parser tests.

### Task 4: Finish Task Tracking and Verification

**Files:**
- Modify: `docs/plans/current-work/complete-shallow-history/TASK.md`

**Steps:**
1. Run focused Maven tests outside the sandbox:
   `mvn test -Pdev -T 4 -q -pl git/git-parser,git/git-native-storage -am -Dtest=GitBlockingWireSessionTest,ProtocolV2PackfileResponseTest,NativeGitRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
2. Run `mvn verify -Pdev -T 4` outside the sandbox if focused checks pass.
3. If variant 3 is fully complete, mark the task node done and remove owner.
   If deeper walker semantics remain, leave it active and add the exact next
   step for variant 2 follow-up.

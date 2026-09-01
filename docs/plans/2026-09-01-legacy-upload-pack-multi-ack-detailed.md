# Legacy Upload-Pack Multi ACK Detailed Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make legacy upload-pack `multi_ack_detailed` negotiation emit protocol-correct ACK status lines before serving the pack.

**Architecture:** Keep parsing in `GitBlockingWireSession`. Track common `have` objects during the legacy negotiation loop, emit ACK lines according to negotiated capability, then reuse the existing `LegacyUploadNegotiation` pack response path.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Orion native Git parser and in-memory native repository tests.

---

### Task 1: Add failing legacy multi_ack_detailed tests

**Files:**
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java`

**Steps:**
1. Add a test where the client requests `multi_ack_detailed`, sends an existing `have`, and then `done`.
2. Assert the response contains `ACK <have> common` before `ACK <have> ready`, followed by pack data.
3. Add a test where the client requests `multi_ack_detailed`, sends only an unknown `have`, and then `done`.
4. Assert the response starts with `NAK` and still serves the requested pack.
5. Run `mvn test -Pdev -T 4 -q -pl git/git-parser -am -Dtest=GitBlockingWireSessionTest -Dsurefire.failIfNoSpecifiedTests=false` and confirm the new tests fail.

### Task 2: Implement legacy ACK negotiation

**Files:**
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify if needed: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`

**Steps:**
1. During legacy negotiation, identify whether a `have` object exists in the selected repository.
2. For `multi_ack_detailed`, send `ACK <id> common` for each common `have` and remember the latest common object.
3. For `multi_ack`, send `ACK <id> continue` for each common `have`.
4. On `done`, send `ACK <latest-common> ready` when a common object was found, otherwise `NAK`.
5. Preserve the existing pack response flow.
6. Run the focused Maven command and confirm it passes.

### Task 3: Finish task tracking

**Files:**
- Modify: `docs/plans/current-work/multi-ack-detailed/TASK.md`
- Modify: `docs/plans/current-work/git-protocol-canonical-parity/TASK.md`

**Steps:**
1. Mark the implemented multi_ack_detailed task complete.
2. Remove the owner line from the completed task.
3. Run `git diff --check`.

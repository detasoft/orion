# Git Wire Response Parsers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extend `GitMinimalWireMachine` with an internal typed value stack and streaming parsers for v1 advertisements, v2 `ls-refs`, and v2 `fetch` responses through side-band pack forwarding.

**Architecture:** Keep pkt-line framing and fragmented `ByteBuf` ownership in `GitMinimalWireMachine`. Add an optional semantic program whose phases consume completed control/payload events, exchange completed values through a machine-owned typed operand stack, and leave one success or failure outcome at termination. A fetch packfile phase delegates framed side-band packets to the existing streaming decoder and never stores band-one payload bytes.

**Tech Stack:** Java 21 records and sealed interfaces, Netty `ByteBuf`, JUnit 5, AssertJ, Maven `dev` profile.

---

### Task 1: Add the Typed Machine Value Stack

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireValueStack.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireFailure.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireOutcome.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireValueStackTest.java`

**Step 1: Write the failing stack handoff test**

Cover push, typed pop, final peek, underflow, and wrong-type access:

```java
@Test
void passesACompletedValueToTheNextPhase() {
    GitWireValueStack stack = new GitWireValueStack();
    GitCapabilitySet capabilities = new GitCapabilitySet(List.of(GitCapability.bare("thin-pack")));

    stack.push(GitCapabilitySet.class, capabilities);

    assertThat(stack.pop(GitCapabilitySet.class)).isSameAs(capabilities);
    assertThat(stack.isEmpty()).isTrue();
}

@Test
void rejectsAValueTypeDifferentFromTheNextPhaseContract() {
    GitWireValueStack stack = new GitWireValueStack();
    stack.push(String.class, "acknowledgments");

    assertThatThrownBy(() -> stack.pop(GitCapabilitySet.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GitCapabilitySet")
            .hasMessageContaining("String");
}
```

The stack should hold `Entry<T>(Class<T>, T)` values rather than expose a raw
`Deque<Object>`. `GitWireOutcome<T>` is a sealed `Success<T>`/`Failure<T>`
result, and `GitWireFailure` wraps a `GitWireError`.

**Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitWireValueStackTest
```

Expected: test compilation fails because the stack and outcome types do not yet
exist.

**Step 3: Implement the minimal typed stack and outcome values**

Use straightforward `ArrayDeque<Entry<?>>` operations. Preserve entries on a
wrong-type access so the machine remains inspectable after an internal
transition error.

**Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all `GitWireValueStackTest` cases pass.

**Step 5: Commit the stack primitive**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireValueStack.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireFailure.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireOutcome.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireValueStackTest.java
git commit -m "Add typed Git wire value stack"
mvn test -Pdev
```

### Task 2: Integrate Semantic Phases into `GitMinimalWireMachine`

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireError.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachineTest.java`

**Step 1: Write failing tests for semantic phase handoff**

Add package-private test semantic phases that demonstrate:

- a data payload producing a value on the stack;
- the following phase consuming that typed value;
- a terminal control packet leaving one completed outcome;
- a `GitWireException` raised by a semantic phase becoming a
  `GitWireFailure` and `FailedPhase`;
- the existing callback constructor still throwing and forwarding exactly as
  before.

The desired internal contract is:

```java
interface SemanticPhase {
    SemanticTransition accept(
            ControlState.ControlSuccess control,
            ByteBuf payload,
            GitWireValueStack values);

    default void close(GitWireValueStack values, long packetIndex, long byteOffset) {
    }
}

sealed interface SemanticTransition {
    record Next(SemanticPhase phase) implements SemanticTransition {}
    record EnterSideBand(SemanticPhase afterSideBand) implements SemanticTransition {}
    record Complete<T>(Class<T> type, T value) implements SemanticTransition {}
}
```

For non-data controls, use `Unpooled.EMPTY_BUFFER`; semantic phases must not
retain the call-scoped payload. The machine releases internally consumed
payloads after the phase returns.

**Step 2: Run the machine test and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitMinimalWireMachineTest
```

Expected: compilation fails for the semantic constructor/factory and state
types.

**Step 3: Implement semantic mode without changing callback behavior**

Add an optional semantic context holding the current semantic phase and
`GitWireValueStack`. Route completed data payloads and special control packets
to it. Add completed and failed wire phases, reject readable input after either
terminal phase, and expose:

```java
public <T> Optional<GitWireOutcome<T>> outcome(Class<T> type)
public <T> T result(Class<T> type)
```

`result` returns a completed value, rethrows a stored failure as
`GitWireException`, and rejects access before termination. Add
`INVALID_ADVERTISEMENT`, `INVALID_PROTOCOL_V2_RESPONSE`, and
`INVALID_PHASE_TRANSITION` error kinds, plus semantic error phases for
advertisement, `ls-refs`, and fetch diagnostics.

**Step 4: Run the focused machine tests and verify GREEN**

Run the command from Step 2, then:

```bash
mvn test -Pdev -q -pl core/git-parser -am
```

Expected: all existing and new `git-parser` tests pass.

**Step 5: Commit the semantic machine integration**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireError.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachineTest.java
git commit -m "Add semantic phases to Git wire machine"
mvn test -Pdev
```

### Task 3: Parse Protocol V1 Reference Advertisements

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/advertisement/GitAdvertisedRef.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/advertisement/GitV1Advertisement.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/advertisement/GitV1AdvertisementPhases.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/advertisement/GitV1AdvertisementMachineTest.java`

**Step 1: Write the failing happy-path and fragmented-input tests**

Construct the machine through:

```java
GitMinimalWireMachine machine =
        GitMinimalWireMachine.forV1Advertisement(UnpooledByteBufAllocator.DEFAULT);
```

Feed a first line with capabilities, an ordinary branch, a tag, its peeled row,
and `0000`. Assert the final `GitV1Advertisement` contains:

```java
assertThat(result.capabilities().names()).containsExactly("multi_ack", "thin-pack");
assertThat(result.refs()).extracting(GitAdvertisedRef::name)
        .containsExactly("HEAD", "refs/heads/main", "refs/tags/v1");
assertThat(result.refs().get(2).peeledObjectId()).contains(PEELED_ID);
assertThat(result.emptyRepository()).isFalse();
```

Repeat the transcript split inside the first pkt-line header, NUL capability
list, a ref payload, and the terminal flush. Release every accepted input buffer
according to the existing machine ownership contract.

**Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitV1AdvertisementMachineTest
```

Expected: compilation fails because the v1 factory and models are missing.

**Step 3: Implement first-line and ref-list phases**

The first-line phase validates a 40-hex SHA-1, ref name, and optional NUL
separator. It invokes `GitCapabilityParser`, pushes `GitCapabilitySet`, and
transitions to the ref-list phase, which pops the capabilities before consuming
later rows.

Store ordinary refs in insertion order and by name for duplicate/peeled lookup.
A row ending in `^{}` updates its already-seen base ref. On flush, push one
`GitV1Advertisement` and complete the machine.

**Step 4: Add failing edge-case tests**

Cover:

- `0000000000000000000000000000000000000000 capabilities^{}` plus
  capabilities and flush;
- capabilities on a second row;
- malformed/non-hex/short SHA-1;
- duplicate ref;
- peeled row without a previously advertised base ref;
- delimiter or response-end before flush;
- close before flush.

Verify each malformed transcript leaves a `GitWireFailure` whose kind is
`INVALID_ADVERTISEMENT`, with the expected packet index and semantic phase.

**Step 5: Implement minimal validation and verify GREEN**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitV1AdvertisementMachineTest
mvn test -Pdev -q -pl core/git-parser -am
```

Expected: all v1 and existing module tests pass.

**Step 6: Commit the v1 parser**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/advertisement \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/advertisement
git commit -m "Parse streaming Git v1 advertisements"
mvn test -Pdev
```

### Task 4: Parse Protocol V2 `ls-refs` Responses

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitLsRefAttribute.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitLsRef.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitLsRefsResponse.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitLsRefsResponsePhases.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitLsRefsResponseMachineTest.java`

**Step 1: Write failing model and streaming tests**

Use:

```java
GitMinimalWireMachine machine =
        GitMinimalWireMachine.forV2LsRefsResponse(UnpooledByteBufAllocator.DEFAULT);
```

Feed rows containing:

```text
<oid> HEAD symref-target:refs/heads/main custom:value
<oid> refs/tags/v1 peeled:<peeled-oid>
unborn refs/heads/new
```

Terminate the structured list with flush and the stateless response with
response-end. Assert typed `symrefTarget`, `peeledObjectId`, `unborn`, and
preserved unknown attributes. Include payload fragmentation.

**Step 2: Run focused tests and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitLsRefsResponseMachineTest
```

Expected: compilation fails because the `ls-refs` response factory and models
are missing.

**Step 3: Implement the response phases**

Parse `obj-id-or-unborn SP refname *(SP attribute)`. Accept 40-hex object ids for
the current native SHA-1 boundary, require a value for `symref-target:` and
`peeled:`, and retain unknown tokens as raw name/value attributes. Flush closes
the ref list; response-end pushes `GitLsRefsResponse` and completes the
machine.

**Step 4: Add malformed response tests**

Cover duplicate refs, invalid object ids, `unborn` with peeled data, missing
attribute values, duplicate known attributes, delimiter, missing flush before
response-end, and close before response-end.

**Step 5: Implement validation and verify GREEN**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitLsRefsResponseMachineTest
mvn test -Pdev -q -pl core/git-parser -am
```

Expected: all tests pass.

**Step 6: Commit the `ls-refs` parser**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/protocolv2/response
git commit -m "Parse streaming Git v2 ls-refs responses"
mvn test -Pdev
```

### Task 5: Parse Structured Protocol V2 Fetch Sections

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchAcknowledgments.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchShallowInfo.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchWantedRef.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchResponse.java`
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchResponsePhases.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchResponseMachineTest.java`

**Step 1: Write a failing acknowledgments-only test**

Feed:

```text
acknowledgments
ACK <oid>
ready
0000
0002
```

Assert the acknowledgment value was passed through the stack into a completed
`GitFetchResponse`, with no packfile.

**Step 2: Run the focused test and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitFetchResponseMachineTest
```

Expected: compilation fails because fetch response types do not exist.

**Step 3: Implement acknowledgments parsing**

Enforce `NAK` versus one-or-more `ACK` exclusivity, validate ACK object ids,
allow one `ready`, and finish an acknowledgments-only negotiation response on
flush followed by response-end.

**Step 4: Add failing multi-section tests**

Feed the ordered sequence:

```text
acknowledgments ... 0001
shallow-info ... 0001
wanted-refs ... 0001
packfile
```

Assert each phase pushes its value and the next phase consumes prior values.
Cover `shallow`, `unshallow`, and `<oid> <refname>` wanted rows.

Add failures for an unknown header, duplicate section, out-of-order section,
malformed line, flush before a required packfile continuation, and delimiter
without a completed section.

**Step 5: Implement section ordering and accumulation**

Use an enum order and ordinary conditional control flow. Do not use streams for
the parser transitions. Preserve the received section order in the final
response.

**Step 6: Run focused structured tests and verify GREEN**

Run the focused command from Step 2. Expected: structured fetch tests pass;
packfile forwarding tests remain pending.

### Task 6: Enter Side-Band Packfile Forwarding and Finish Fetch

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/sideband/GitSideBandDecoder.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchResponsePhases.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/protocolv2/response/GitFetchResponseMachineTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/sideband/GitSideBandTest.java`

**Step 1: Write the failing packfile streaming test**

Create a fetch machine with a lazy band-one `RawSink.Target` factory and progress
consumer. Feed the `packfile` header followed by fragmented side-band packets:

- band 1 with `PACK` bytes split across input buffers;
- band 2 progress;
- side-band flush;
- response-end.

Assert:

- the raw target receives exact pack bytes as retained slices;
- progress is delivered separately;
- `GitFetchResponse.packfileReceived()` is true;
- the response model contains no pack byte array or `ByteBuf`;
- target creation remains lazy until the first band-one payload byte;
- response-end completes the outer v2 response after the side-band decoder
  stops at its flush.

**Step 2: Run the fetch and side-band tests and verify RED**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am \
  -Dtest=GitFetchResponseMachineTest,GitSideBandTest
```

Expected: the machine cannot yet enter and resume from a side-band phase, and
the decoder creates its target too early.

**Step 3: Add lazy side-band target creation**

Change `GitSideBandDecoder` to accept a target factory/supplier and instantiate
the target only for non-empty band-one data. Preserve its current constructor as
a compatibility overload. Ensure decoder completion leaves unread outer
response bytes in the input buffer.

**Step 4: Add `SideBandPhase` to the wire machine**

The phase owns the decoder and the semantic phase to resume after side-band
flush. It passes the same input buffer to the decoder, observes completion, then
returns to `ControlPhase` so a response-end packet in the same input chunk is
consumed by the fetch semantic phase.

On close, close the decoder and raw target exactly once. Convert side-band fatal
and malformed packets into the typed failure stack in semantic mode while
retaining legacy decoder exception behavior outside the machine.

**Step 5: Run focused and module tests and verify GREEN**

Run:

```bash
mvn test -Pdev -q -pl core/git-parser -am \
  -Dtest=GitFetchResponseMachineTest,GitSideBandTest,GitMinimalWireMachineTest
mvn test -Pdev -q -pl core/git-parser -am
```

Expected: all focused and module tests pass.

**Step 6: Commit the fetch parser**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/sideband/GitSideBandDecoder.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/protocolv2/response \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/protocolv2/response \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/sideband/GitSideBandTest.java
git commit -m "Parse streaming Git v2 fetch responses"
mvn test -Pdev
```

### Task 7: Complete Tracking and Repository Verification

**Files:**
- Modify: `TASKS.md`
- Modify if necessary: `docs/plans/2026-07-27-git-wire-response-parsers-design.md`

**Step 1: Run formatting and dependency-boundary checks**

Run:

```bash
git diff --check
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitWireCoreBoundaryTest
```

Expected: no whitespace errors and no production JGit dependency.

**Step 2: Run routine repository verification**

Run outside the sandbox:

```bash
mvn verify -Pdev
```

Expected: `BUILD SUCCESS`.

**Step 3: Update high-level task tracking**

Mark both parser subtasks complete and remove this task's owner line. Keep the
parent task unchecked because other native wire-core work remains.

**Step 4: Commit tracking**

```bash
git add TASKS.md docs/plans/2026-07-27-git-wire-response-parsers-design.md
git commit -m "Complete Git wire response parser task"
mvn test -Pdev
```

**Step 5: Review the final branch**

Run:

```bash
git status --short
git log --oneline main..HEAD
git diff --stat main...HEAD
```

Expected: a clean worktree containing only the design, parser implementation,
tests, and scoped `TASKS.md` updates.

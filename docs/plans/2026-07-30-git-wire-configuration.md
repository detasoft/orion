# Git Wire Configuration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a code-only configuration object that consistently controls advertised and usable Git protocol v0/v1 and v2 features.

**Architecture:** Introduce an immutable `GitWireConfiguration` with separate legacy upload-pack, legacy receive-pack, and protocol v2 sections. Pass one configuration instance through `GitMinimalWireMachine.Context`; advertisements and continuation dispatch consult that same instance so published capabilities cannot drift from runtime behavior. Existing constructors retain compatibility by using `allSupported()`.

**Tech Stack:** Java 21 records, JUnit 5, AssertJ, Maven.

---

### Task 1: Add the immutable configuration model

**Files:**
- Create: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireConfiguration.java`
- Create: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireConfigurationTest.java`

**Step 1: Write the failing configuration tests**

Cover:

```java
@Test
void allSupportedEnablesEveryCurrentFeature() {
    GitWireConfiguration configuration =
            GitWireConfiguration.allSupported();

    assertThat(configuration.uploadPack().multiAckDetailed()).isTrue();
    assertThat(configuration.receivePack().reportStatus()).isTrue();
    assertThat(configuration.protocolV2().lsRefs()).isTrue();
    assertThat(configuration.protocolV2().lsRefsUnborn()).isTrue();
    assertThat(configuration.protocolV2().fetch()).isTrue();
    assertThat(configuration.protocolV2().serverOption()).isTrue();
}

@Test
void rejectsUnbornWithoutLsRefs() {
    assertThatThrownBy(() -> new GitWireConfiguration.ProtocolV2(
            false, true, true, true))
            .isInstanceOf(IllegalArgumentException.class);
}
```

Also verify that null nested sections are rejected.

**Step 2: Run the focused test to verify it fails**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitWireConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `GitWireConfiguration` does not exist.

**Step 3: Implement the configuration records**

Add:

```java
public record GitWireConfiguration(
        LegacyUploadPack uploadPack,
        LegacyReceivePack receivePack,
        ProtocolV2 protocolV2) {

    public GitWireConfiguration {
        Objects.requireNonNull(uploadPack, "uploadPack");
        Objects.requireNonNull(receivePack, "receivePack");
        Objects.requireNonNull(protocolV2, "protocolV2");
    }

    public static GitWireConfiguration allSupported() {
        return new GitWireConfiguration(
                new LegacyUploadPack(true, true, true, true, true, true),
                new LegacyReceivePack(true, true, true, true, true),
                new ProtocolV2(true, true, true, true));
    }

    public record LegacyUploadPack(
            boolean multiAckDetailed,
            boolean thinPack,
            boolean sideBand64k,
            boolean ofsDelta,
            boolean symref,
            boolean agent) {
    }

    public record LegacyReceivePack(
            boolean reportStatus,
            boolean sideBand64k,
            boolean ofsDelta,
            boolean objectFormat,
            boolean agent) {
    }

    public record ProtocolV2(
            boolean lsRefs,
            boolean lsRefsUnborn,
            boolean fetch,
            boolean serverOption) {
        public ProtocolV2 {
            if (lsRefsUnborn && !lsRefs) {
                throw new IllegalArgumentException(
                        "lsRefsUnborn requires lsRefs");
            }
        }
    }
}
```

**Step 4: Run the focused tests**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireConfiguration.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireConfigurationTest.java
git commit -m "Add Git wire feature configuration"
```

### Task 2: Propagate configuration through the wire machine

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachineTest.java`
- Modify test context call sites under: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/`

**Step 1: Write failing constructor and context tests**

Verify that:

- existing constructors and `testContext` use `allSupported()`;
- a new constructor accepts an explicit `GitWireConfiguration`;
- `Context.configuration` is the exact supplied instance.

**Step 2: Run the focused test to verify it fails**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitMinimalWireMachineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the configuration constructor and context field do not
exist.

**Step 3: Add constructor delegation and context storage**

Add a four-argument constructor:

```java
public GitMinimalWireMachine(
        ByteBufAllocator allocator,
        GitNativeClientOutput clientOutput,
        InMemoryNativeGitRepositoryProvider repositoryProvider,
        GitWireConfiguration configuration)
```

Make existing constructors delegate with `allSupported()`. Add the required
`@TestOnly` factory overload, and store the non-null configuration on
`Context`.

**Step 4: Run the focused test**

Expected: PASS.

**Step 5: Commit**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/
git commit -m "Propagate Git wire feature configuration"
```

### Task 3: Configure legacy advertisements and negotiation

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackIngestionContinuation.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryServiceTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackIngestionContinuationTest.java`

**Step 1: Write failing advertisement tests**

Create configurations that disable one capability at a time. Assert stable
advertisement order and absence of:

- upload-pack: `multi_ack_detailed`, `thin-pack`, `side-band-64k`,
  `ofs-delta`, `agent`, and dynamic `symref`;
- receive-pack: `report-status`, `side-band-64k`, `ofs-delta`,
  `object-format`, and `agent`.

Retain one test proving `allSupported()` produces the current advertisement.

**Step 2: Run service tests to verify they fail**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeRepositoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because advertisements are still hard-coded.

**Step 3: Build legacy capability lists from configuration**

Store the configuration in `GitNativeRepositoryService`. Replace static
capability lists with ordinary loops that append enabled values in the current
wire order. Gate dynamic `symref` on `uploadPack.symref()`.

Keep the existing service constructor as an `allSupported()` convenience
constructor.

**Step 4: Run service tests**

Expected: PASS.

**Step 5: Write failing receive negotiation tests**

Verify that requesting disabled `side-band-64k` or `report-status` does not
activate that response mode even when the client includes the token.

**Step 6: Gate receive behavior with the same configuration**

Consult `context.configuration.receivePack()` in
`ReceivePackIngestionContinuation` when selecting side-band and report-status
behavior.

This is a `Continuation` change, so add or update its tests after the
production logic, as required by repository rules.

**Step 7: Run legacy continuation tests**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=ReceivePackIngestionContinuationTest,UploadNegotiationContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 8: Commit**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackIngestionContinuation.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryServiceTest.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v0v1/ReceivePackIngestionContinuationTest.java
git commit -m "Configure legacy Git wire capabilities"
```

### Task 4: Configure protocol v2 advertisement

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuation.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java`

**Step 1: Write failing output tests**

Test exact output for:

- all supported: `ls-refs=unborn`, `fetch`, `server-option`;
- `lsRefs=true`, `lsRefsUnborn=false`: plain `ls-refs`;
- disabled `lsRefs`, `fetch`, or `serverOption`: omitted line;
- every combination still ending in a flush packet.

**Step 2: Run output tests to verify they fail**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeClientOutputTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because advertisement is hard-coded.

**Step 3: Serialize v2 capabilities from configuration**

Change `sendV2UploadPackAdvertisement` to accept
`GitWireConfiguration.ProtocolV2`. Build the packet list with an ordinary loop
and stable ordering. Update `UploadPackContinuation` to supply
`context.configuration.protocolV2()`.

Because `UploadPackContinuation` is a `Continuation`, make the production
change before updating its continuation-specific tests.

**Step 4: Add/update continuation tests and run focused tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=GitNativeClientOutputTest,UploadPackContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitNativeClientOutput.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuation.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitNativeClientOutputTest.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java
git commit -m "Configure protocol v2 advertisement"
```

### Task 5: Gate v2 commands and ls-refs features

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandContinuation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsContinuation.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsContinuationTest.java`

**Step 1: Implement command and argument gates**

In `UploadCommandContinuation.completeCommand`, reject `LS_REFS` when
`protocolV2.lsRefs()` is false and reject `FETCH` when
`protocolV2.fetch()` is false.

In `LsRefsContinuation.acceptArgument`, reject `unborn` when
`protocolV2.lsRefsUnborn()` is false. Keep unknown well-formed ls-refs
arguments ignored as required by the existing parser behavior.

These are `Continuation` changes, so production logic comes before tests.

**Step 2: Add command and unborn tests**

Cover enabled and disabled dispatch for both commands, plus disabled `unborn`
with otherwise valid `ls-refs`.

**Step 3: Run focused continuation tests**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=UploadPackContinuationTest,LsRefsContinuationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 4: Commit**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandContinuation.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsContinuation.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/LsRefsContinuationTest.java
git commit -m "Gate protocol v2 features by configuration"
```

### Task 6: Parse the server-option request header

**Files:**
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandContinuation.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandPayloadContinuation.java`
- Modify: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java`

**Step 1: Extend production header parsing**

Refactor the payload parser result from a command-only value into a request
header value that distinguishes:

```java
sealed interface Header {
    record Command(UploadCommandContinuation.Command value)
            implements Header {}
    record ServerOption(String value) implements Header {}
}
```

Require `command=...` to be the first header. Permit later
`server-option=<non-empty ASCII value>` headers before the delimiter.

When `serverOption` is disabled, transition to the existing invalid protocol
v2 error. When it is enabled and a valid header is received, throw exactly:

```java
throw new IllegalStateException("not implemented");
```

Do not throw during machine construction or advertisement.

**Step 2: Add tests after the continuation implementation**

Cover:

- disabled `server-option` produces the normal invalid-request flow;
- enabled `server-option` is advertised and machine creation succeeds;
- processing a valid enabled header throws
  `IllegalStateException("not implemented")`;
- malformed, empty, non-ASCII, and pre-command server-option headers are
  rejected as invalid requests;
- ordinary command-only requests remain fragment-safe.

**Step 3: Run focused continuation tests**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am \
  -Dtest=UploadPackContinuationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 4: Commit**

```bash
git add core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandContinuation.java \
  core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadCommandPayloadContinuation.java \
  core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/continuation/v2/UploadPackContinuationTest.java
git commit -m "Handle configured protocol v2 server option"
```

### Task 7: Verify the complete change

**Files:**
- Modify only if verification exposes a defect in the files above.

**Step 1: Run the complete git-parser test suite**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl core/git-parser -am
```

Expected: PASS.

**Step 2: Run routine development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: PASS, except that external integration tests may report an
environmental network failure. If that happens, record the exact failure and
also run:

```bash
mvn verify -Pdev -T 4 -DskipITs
```

**Step 3: Check the patch**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and only intended files changed.

**Step 4: Request code review**

Use `superpowers:requesting-code-review` and address any findings before
claiming completion.


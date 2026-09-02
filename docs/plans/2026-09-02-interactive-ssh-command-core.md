# Interactive SSH Command Core Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a reusable `core/command` pipeline and route every non-Git SSH exec request through it while
preserving existing Git and administrative command behavior.

**Architecture:** The new Maven module owns immutable command requests, parsing, hierarchical dispatch, scoped
resource resolution, structured results, rendering contracts, cancellation, and auditing. `net/git-transport`
owns Mina-specific request construction, compatibility command handlers, logging audit output, and stream/exit
delivery; Git wire commands continue through their existing handler.

**Tech Stack:** Java 21 records and sealed interfaces, Orion authorization contracts, Dagger 2, Apache Mina SSHD
2.13.2, JUnit 5, AssertJ, Maven.

---

### Task 1: Add the command module and immutable request/result contracts

**Files:**
- Modify: `bom/pom.xml`
- Modify: `core/pom.xml`
- Create: `core/command/pom.xml`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandCancellation.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandContext.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandPath.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandPresentation.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandRequest.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandResult.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandFailureCode.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/CommandModelTest.java`

**Step 1: Write the failing model tests**

Cover defensive copies, required context fields, root and relative paths, result payload immutability, and the
documented finite/stream/attachment result variants. Use a minimal authenticated `UserIdentity` fixture.

The public shape should begin with:

```java
public record CommandRequest(String commandLine, CommandContext context) {}

public record CommandContext(
        SecurityContext securityContext,
        String requestId,
        String sessionId,
        String sourceAddress,
        CommandPath currentPath,
        CommandPresentation presentation,
        CommandCancellation cancellation,
        Map<String, String> auditMetadata) {}

public sealed interface CommandResult {
    record Message(String value) implements CommandResult {}
    record Rows(List<String> columns, List<List<String>> values) implements CommandResult {}
    record ObjectValue(Map<String, String> fields) implements CommandResult {}
    record Stream(Object handle) implements CommandResult {}
    record Attachment(Object handle) implements CommandResult {}
    record Exit(int exitCode, String message) implements CommandResult {}
    record Failure(CommandFailureCode code, String message, List<String> candidates)
            implements CommandResult {}
}
```

Replace raw `Object` handles with small marker interfaces in `CommandResult` so later tasks can implement them
without changing the sealed result variants.

**Step 2: Run the focused test and confirm the module is missing**

Run:

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=CommandModelTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `core/command` and its model types do not exist.

**Step 3: Add Maven wiring and minimal model implementations**

Register `pro.deta.orion.core:command` in the BOM and `core/pom.xml`. The module depends only on
`core/authorization` and `slf4j-api`; it must not depend on Mina, `net/*`, bootstrap, repository storage, or
concrete ACL storage. Implement compact constructors with `Objects.requireNonNull`, `List.copyOf`, and
`Map.copyOf`. Normalize the empty path to `/` and reject empty non-root path segments.

**Step 4: Run the focused model test**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add bom/pom.xml core/pom.xml core/command
git commit -m "Add transport-independent command model"
```

### Task 2: Implement deterministic Orion command parsing

**Files:**
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandAction.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandLineParser.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandParseResult.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/ParsedCommand.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/WherePredicate.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/CommandLineParserTest.java`

**Step 1: Write parser tests first**

Cover:

- absolute `/repository/team show` and relative `repository/team show` paths;
- short root commands such as `whoami`;
- the six reserved actions: `ls`, `show`, `add`, `rm`, `attach`, and `monitor`;
- positional arguments and ordered `name=value` parameters;
- `where state=running owner!=bot` as conjunction-only predicates;
- single quotes, double quotes, whitespace escapes, and literal shell metacharacters;
- root-relative `..` normalization and rejection of traversal above `/`;
- unterminated quotes, duplicate parameters, invalid predicates, and tokens after a malformed `where` clause.

Assert structured `CommandParseResult.Failure` values rather than thrown exceptions.

**Step 2: Run the parser test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=CommandLineParserTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the parser types are absent.

**Step 3: Implement a small state-machine tokenizer and parser**

Use ordinary loops. Do not invoke a shell tokenizer and do not assign special behavior to `|`, `>`, `<`, `;`,
`$`, backticks, globs, or environment syntax. Parse into:

```java
public record ParsedCommand(
        CommandPath path,
        String action,
        List<String> positionalArguments,
        Map<String, String> namedParameters,
        List<WherePredicate> predicates) {}
```

Keep the parser independent of command registration. The dispatcher, not the tokenizer, decides whether an
action or path exists.

**Step 4: Run parser and module tests**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/command/src
git commit -m "Parse Orion command lines"
```

### Task 3: Add ACL-aware scoped resource resolution

**Files:**
- Create: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceCandidate.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceCatalog.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceResolution.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceResolver.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/resource/ScopedResourceResolverTest.java`

**Step 1: Write failing resolution tests**

Use candidates with full IDs, optional names, payload values, and `AccessDecision` values. Prove that:

- exact full ID wins over prefix and name matches;
- a unique allowed ID prefix resolves;
- an allowed unique name resolves only when name matching is enabled;
- ambiguous prefixes return only allowed candidate IDs;
- denied candidates never resolve and never appear in ambiguity output;
- missing and all-denied selections are indistinguishable to avoid leaking existence;
- `visible` independently filters list candidates with the same access decision.

**Step 2: Run the focused test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=ScopedResourceResolverTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the resolver is absent.

**Step 3: Implement the resolver algorithm**

`ScopedResourceCatalog` supplies candidates for the current authenticated context and resolved parent scope.
Keep matching in the core resolver. Use ordinary loops and preserve catalog order. Return a sealed resolution:

```java
public sealed interface ScopedResourceResolution<T> {
    record Resolved<T>(ScopedResourceCandidate<T> candidate)
            implements ScopedResourceResolution<T> {}
    record Missing<T>() implements ScopedResourceResolution<T> {}
    record Ambiguous<T>(List<String> candidateIds)
            implements ScopedResourceResolution<T> {}
}
```

**Step 4: Run all command module tests**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/command/src
git commit -m "Resolve scoped command resources"
```

### Task 4: Build the hierarchical dispatcher and audit wrapper

**Files:**
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandArguments.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandAuthorization.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandDefinition.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandDispatcher.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandHandler.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandInvocation.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandNode.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/DefaultCommandDispatcher.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/audit/AuditingCommandDispatcher.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/audit/CommandAuditRecord.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/audit/CommandAuditSink.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/DefaultCommandDispatcherTest.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/audit/AuditingCommandDispatcherTest.java`

**Step 1: Write failing dispatcher tests**

Build a small tree with static nodes, one dynamic scoped child, root aliases, and handlers that return each finite
result kind. Cover successful dispatch, relative context paths, unknown path/action, invalid arity and parameter
names, ambiguous and missing resources, denied actions, cancellation before handler invocation, and handler
exceptions converted to `HANDLER_FAILED` without leaking exception messages.

`CommandDefinition` should contain its action name, positional range, allowed named parameters, sensitive names,
visibility predicate, authorization callback, and handler. `CommandInvocation` exposes only core command values,
the authenticated context, and resolved resource payloads.

**Step 2: Run the dispatcher test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=DefaultCommandDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because command tree and dispatcher types are missing.

**Step 3: Implement hierarchical tree walking and dispatch**

Use immutable builder output for `CommandNode`. Return `CommandResult.Failure` for every expected dispatch
failure. Catch unexpected handler exceptions once at the dispatcher boundary. Recheck `CommandCancellation`
before invoking the handler. Visibility must not bypass `CommandAuthorization`.

**Step 4: Write failing audit and redaction tests**

Prove one audit record per success, expected failure, cancellation, and handler exception. Assert user ID,
request/session/source, normalized path/action, result kind/code, and non-negative duration. Mark a fake
`secret=value` parameter sensitive and assert the audit record contains `<redacted>` and never the secret.
Assert a throwing audit sink does not replace the original command result.

**Step 5: Implement the audit decorator**

Wrap an existing dispatcher:

```java
public final class AuditingCommandDispatcher implements CommandDispatcher {
    private final CommandDispatcher delegate;
    private final CommandAuditSink sink;
    private final LongSupplier nanoTime;

    @Override
    public CommandResult dispatch(CommandRequest request) {
        // time, delegate, redact using matched definition metadata, record once
    }
}
```

Do not store or log the raw command line. Sink failures may be logged through SLF4J but must not change output.

**Step 6: Run command module tests**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am
```

Expected: PASS.

**Step 7: Commit**

```bash
git add core/command/src
git commit -m "Dispatch and audit Orion commands"
```

### Task 5: Add the stable plain renderer

**Files:**
- Create: `core/command/src/main/java/pro/deta/orion/command/render/PlainCommandRenderer.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/render/RenderedCommand.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/render/PlainCommandRendererTest.java`

**Step 1: Write failing renderer tests**

Specify UTF-8 text without ANSI codes:

- messages end with one newline unless empty;
- rows render one tab-separated row per line with stable column order;
- objects render `name=value` lines in insertion order;
- explicit exits preserve their code and route non-empty messages to stderr;
- failures use stable symbolic prefixes and exit codes;
- stream and attachment reservations return `UNSUPPORTED_RESULT` without invoking their handles.

Use this exit mapping: success `0`, service/internal failure `1`, invalid input `2`, missing `3`, ambiguous `4`,
access denied `10`, cancelled `125`, unsupported result `126`, and unknown command/path `127`.

**Step 2: Run the renderer test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=PlainCommandRendererTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because renderer types are absent.

**Step 3: Implement renderer and immutable rendered payload**

`RenderedCommand` carries stdout text, stderr text, and exit code. Avoid terminal detection and styling. Do not
serialize throwable messages or raw parameters.

**Step 4: Run command module tests**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/command/src
git commit -m "Render stable plain command output"
```

### Task 6: Register compatibility commands and Dagger wiring

**Files:**
- Modify: `net/git-transport/pom.xml`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalog.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/Slf4jCommandAuditSink.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/SshCommandModule.java`
- Modify: `net/transport/src/main/java/pro/deta/orion/transport/OrionTransportModule.java`
- Test: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalogTest.java`
- Modify: `net/transport/src/test/java/pro/deta/orion/transport/OrionTransportModuleTest.java`

**Step 1: Write failing compatibility catalog tests**

Use fakes for `OrionProvider`, `OrionAccessControlService`, the runtime state machine, and repository provider.
Cover:

- `issue-token 600` and `token 600` returning the issued token;
- invalid, zero, and extra token expiry arguments returning invalid-input results;
- `state` and `status` returning the existing lifecycle description;
- `repositories` returning the same ordered names;
- `shutdown` scheduling application shutdown only after authorization;
- anonymous and ordinary-user denial returning `ACCESS_DENIED`;
- sensitive values absent from the logging audit sink payload.

**Step 2: Run the focused test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest=LegacySshCommandCatalogTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the catalog is absent and `git-transport` does not depend on `core/command`.

**Step 3: Add the dependency and compatibility catalog**

Build root aliases backed by small handlers. Reuse existing Orion ACL rules and `AccessEnforcer`; catch
`OrionSecurityException` at the handler boundary and return a structured denial. Keep token values in command
results but never audit result payloads. Use a Dagger module to provide the immutable command tree, base
dispatcher, audit decorator, parser, and renderer as singletons.

**Step 4: Add Dagger module integration test**

Extend `OrionTransportModuleTest` to verify the transport module includes command bindings without accepting a
concrete lifecycle service. Run:

```bash
mvn test -Pdev -T 4 -q -pl net/transport -am \
  -Dtest=OrionTransportModuleTest,LegacySshCommandCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Commit**

```bash
git add net/git-transport/pom.xml net/git-transport/src net/transport/src
git commit -m "Register SSH compatibility commands"
```

### Task 7: Route non-Git SSH exec through the command pipeline

**Files:**
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`

**Step 1: Write failing SSH adapter tests**

Inject a recording `CommandDispatcher` and assert a non-Git exec creates a request with authenticated identity,
distinct request/session IDs, remote source address, root current path, plain non-interactive presentation, and
interrupt-aware cancellation. Assert stdout, stderr, flush, and exit status match `RenderedCommand`.

Also cover missing identity, cancellation, handler failure, output write failure, and executor rejection. Preserve
the existing Git protocol error tests and prove `git-upload-pack` and `git-receive-pack` never enter the Orion
dispatcher.

**Step 2: Run focused SSH tests and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest=SshCommandFactoryTest,GitSshTransportStateMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `OtherSshCommand` still parses and executes commands directly.

**Step 3: Replace direct non-Git execution with the adapter**

Keep `GitSshCommand` and Git bootstrap logic unchanged. The non-Git command should:

1. build `SecurityContext` from `SSH_AUTHENTICATED_USER`;
2. build immutable SSH metadata without passing Mina objects into the core;
3. dispatch on `OrionExecutor`;
4. render via `PlainCommandRenderer`;
5. write and flush stdout/stderr;
6. invoke `ExitCallback` exactly once.

Remove the old inline token, state, repositories, shutdown, parsing, and error branches after their catalog tests
are green. Expected delivery failures remain local to the SSH adapter and complete with exit code `1`.

**Step 4: Extend the real SSH compatibility test**

Add one end-to-end assertion for stable unknown-command output and exit `127`. Retain the existing token, state,
repositories, shutdown, enrollment, named-user denial, and Git push/clone/pull tests as compatibility coverage.

Run the whole class explicitly:

```bash
mvn verify -Pdev -T 4 -q -pl tests/integration-test -am \
  -Dit.test=GitSshTransportEndToEndIT \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dexec.skip=true
```

Expected: all `GitSshTransportEndToEndIT` tests PASS.

**Step 5: Run focused module tests**

```bash
mvn test -Pdev -T 4 -q -pl core/command,net/git-transport,net/transport -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 6: Commit**

```bash
git add net/git-transport/src tests/integration-test/src
git commit -m "Route SSH exec through command dispatcher"
```

### Task 8: Review, verify, and finish the task node

**Files:**
- Delete after verification:
  `docs/plans/current-work/interactive-ssh-shell/command-core-and-exec/TASK.md`
- Modify after verification: `docs/plans/current-work/interactive-ssh-shell/TASK.md`

**Step 1: Review repository rules and changed classes**

Read `docs/reviews/RULES.md`. Search changed classes for `@AiRule` and verify every applicable invariant. Check
source lines at 112 characters, allowing only justified exceptions up to 135. Run `git diff --check`.

**Step 2: Run routine development verification**

```bash
mvn verify -Pdev -T 4
```

Expected: PASS, or report clearly isolated pre-existing/external integration failures with focused task tests
remaining green.

**Step 3: Run the required full project tests**

```bash
make test
```

Expected: BUILD SUCCESS.

**Step 4: Finish with the dedicated-worktree workflow**

Remove the completed leaf task directory and its parent link. Squash all task-branch commits into one commit:

```text
Implement the interactive SSH command core [task: interactive-ssh-shell/command-core-and-exec]
```

Cherry-pick that commit onto current `main`, resolve only genuine overlaps while preserving newer `main` work,
run `make test` on `main`, then remove this worktree and its branch. Confirm `main` is clean and the completed
worktree no longer appears in `git worktree list` before reporting completion.

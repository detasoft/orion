# Read-Only Domain Commands Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add structured, ACL-filtered read-only Orion commands with safe scoped resolution and explicit behavior
for domain services that are not available yet.

**Architecture:** Extend the command resource resolver with typed unavailable/failure states, then compose a
Mina-independent read-only command catalog over immutable operator-domain views. A default source adapts established
repository, lifecycle, and JVM APIs; organization, session, and proxy sources remain explicitly unavailable until
their owning runtime tasks supply adapters.

**Tech Stack:** Java 21, Orion command and authorization cores, Apache Mina SSHD frontend composition, Dagger,
JUnit 5, AssertJ, Maven.

---

## Non-negotiable behavior

- All commands require an authenticated named Orion user; no command accepts a target-user override.
- Authorization is applied to every concrete resource before it can affect rows, counts, ambiguity candidates,
  navigation, or completion.
- Exact lookup of a denied resource is reported exactly like a missing resource.
- An available empty source succeeds with empty rows. An unavailable source returns `SERVICE_UNAVAILABLE`. An
  unexpected source failure returns a sanitized `HANDLER_FAILED` result.
- IDs used as dynamic path segments must be stable and path-safe. Keep the backing repository name separately for
  ACL evaluation; percent-encode unsafe native repository-name bytes for the path ID and expose the original name as
  a non-path data field. Register a display name as a path alias only when it is one non-special path segment.
- Global lists and object fields have deterministic ordering. Do not expose exception messages, internal file paths,
  grant expressions, candidate objects, or inaccessible-resource counts.
- Escape backslashes and control characters in structured row/object presentation so each value remains within its
  field and interactive output cannot contain source-provided terminal controls. Keep legacy message formatting.
- Keep existing `state`, `status`, `repositories`, token, and `/auth/key` behavior compatible.
- Do not implement query predicates, pagination, JSON, streaming, session attachment, or a new general expression
  language in this task.

### Task 1: Propagate resource-catalog availability through command navigation

**Files:**

- Create: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceCatalogResult.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceCatalog.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceResolution.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/resource/ScopedResourceResolver.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandNavigation.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandFailureCode.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandNavigator.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/DefaultCommandDispatcher.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/resource/ScopedResourceResolverTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/CommandNavigatorTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/DefaultCommandDispatcherTest.java`

**Step 1: Specify the immutable catalog result**

Add failing tests for an immutable sealed result with these shapes:

```java
sealed interface ScopedResourceCatalogResult<T> {
    record Available<T>(List<ScopedResourceCandidate<T>> candidates) implements ScopedResourceCatalogResult<T> {}
    record Unavailable<T>(String source) implements ScopedResourceCatalogResult<T> {}
    record AccessDenied<T>(String reason) implements ScopedResourceCatalogResult<T> {}
    record Failed<T>(String source, Throwable throwable) implements ScopedResourceCatalogResult<T> {}
}
```

Require non-null values, copied candidate lists, and non-blank source identifiers. Keep the throwable for internal
diagnostics; no result renderer may expose its message.

Change `ScopedResourceCatalog.candidates(...)` to return this result. Update existing test lambdas and catalogs to
wrap their lists in `Available` rather than adding an untyped exception convention.

**Step 2: Specify resolver and navigator propagation**

Add failing resolver tests proving that:

- available candidates retain exact-ID, visible-prefix, name, and ambiguity behavior;
- unavailable and failed results are propagated without converting them to an empty list;
- visibility and completion do not return candidates from unavailable or failed sources.

Add corresponding `Unavailable`, `AccessDenied`, and `Failed` variants to `ScopedResourceResolution` and
`CommandNavigation`. `CommandNavigator.locate` and `navigate` propagate them. `visibleEntries` and completion return
no dynamic entries for any unavailable, denied, or failed state and do not throw. Interactive path-only navigation
renders the same sanitized failure codes as dispatcher execution.

**Step 3: Specify dispatcher failures**

Add `SERVICE_UNAVAILABLE` to `CommandFailureCode`. Test that dispatcher navigation maps:

- `CommandNavigation.Unavailable` to `SERVICE_UNAVAILABLE` with the generic message
  `Resource service is unavailable`;
- `CommandNavigation.AccessDenied` to `ACCESS_DENIED` with the generic message `Access denied`;
- `CommandNavigation.Failed` to `HANDLER_FAILED` with `Resource lookup failed`;
- neither source identifier nor throwable message appears in the result.

Keep the existing `MISSING_RESOURCE`, `AMBIGUOUS_RESOURCE`, and `UNKNOWN_PATH` mappings unchanged.

**Step 4: Verify RED**

Run outside the sandbox:

```sh
make run-test MODULE=core/command \
  TEST='ScopedResourceResolverTest,CommandNavigatorTest,DefaultCommandDispatcherTest'
```

Expected: FAIL because catalogs can currently return only a list and navigation has no availability variants.

**Step 5: Implement and verify GREEN**

Use ordinary `switch`/loops and exhaustive sealed-type handling. Do not log or render failure causes from the command
core. Repeat the focused command and require all selected tests to pass.

**Step 6: Commit**

```sh
git add core/command
git commit -m "Propagate command resource availability"
```

### Task 2: Define immutable operator views and established runtime adapters

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/OperatorQueryResult.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/OperatorDomainSource.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/OperatorDomainViews.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/RuntimeMetrics.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/DefaultRuntimeMetrics.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/DefaultOperatorDomainSource.java`
- Create: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/read/OperatorDomainContractsTest.java`
- Create: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/read/DefaultOperatorDomainSourceTest.java`

**Step 1: Specify query result and view contracts**

Add failing tests for `OperatorQueryResult<T>` with `AvailableSnapshot`, scalar `AvailableValue`, `Unavailable`, and
`Failed` variants. `AvailableSnapshot` must defensively copy its list, and `AvailableValue` must be type-bounded so a
list-valued source cannot bypass snapshotting. Require non-null available values, a non-blank stable source name for
unavailable/failed results, and a non-null cause only for `Failed`.

Define public nested records in `OperatorDomainViews` so the package has one coherent vocabulary:

```text
RepositoryView: id, optional name alias, repositoryName, defaultHead, refCount, optional organizationId
OrganizationView: id, optional name
UserView: id, optional name, organizationId, principalId
SessionView: id, optional name, state, ownerId, optional repositoryName
ProxyView: id, optional name, state, optional repositoryName, remote
SystemResourceView: availableProcessors, heapUsedBytes, heapCommittedBytes, heapMaxBytes
ServiceView: id, name, state, computedState, terminal
```

Validate parser-addressable single-segment IDs and aliases, required strings, and non-negative counts, and copy
optionals/collections. Every query-result `toString()` must be metadata-only and must not include values, collection
sizes, throwable details, or any future secret-bearing source state.

`OperatorDomainSource` exposes one query per collection plus system resources. Organization-local users and
repositories accept the canonical organization ID, never a display name.

**Step 2: Specify path-safe repository identities**

In source tests, include native repository names `demo` and `internal/configuration`. Require stable percent-encoded
path IDs (`demo`, `internal%2Fconfiguration`), original repository names for ACL checks/output, and name aliases only
for values that are safe single path segments. Assert deterministic ID ordering independent of provider order.

Do not use `URLEncoder` form semantics (`+` for space). Encode UTF-8 bytes outside the command path's conservative
unreserved set and use uppercase hex.

**Step 3: Specify default sources**

The default source adapts:

- `NativeGitRepositoryProvider.repositoryNames()` and `find()` for repositories;
- an injected `RuntimeMetrics` for processor and heap values;
- `AggregateStateMachine.status()` for a recursively flattened lifecycle service list whose IDs are hierarchical
  slash-free path tokens joined with `.`, whose display name is the state-machine name, and whose machine is obtained
  by exact direct-child key rather than recursive name lookup.

If one listed repository cannot be opened, return `Failed("repository", cause)` for the whole snapshot rather than a
partial list. Catch only the provider/result boundary needed to translate failure; do not catch VM errors.

Return `Unavailable("organization")`, `Unavailable("session")`, and `Unavailable("proxy")` from the default source.
Do not scan configuration XML, journals, Git sync state, or task files to synthesize those domains.

**Step 4: Verify RED**

Run outside the sandbox:

```sh
make run-test MODULE=net/git-transport \
  TEST='OperatorDomainContractsTest,DefaultOperatorDomainSourceTest'
```

Expected: FAIL because no operator query contracts or adapters exist.

**Step 5: Implement and verify GREEN**

Implement the minimal records and adapters, including stable recursive lifecycle ordering. Repeat the focused command
and require all selected tests to pass.

**Step 6: Commit**

```sh
git add net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read \
  net/git-transport/src/test/java/pro/deta/orion/transport/git/command/read
git commit -m "Add operator domain read sources"
```

### Task 3: Add ACL-filtered read-only command catalog

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/ReadOnlyDomainCommandCatalog.java`
- Create: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/ReadOnlyDomainCommandCatalogTest.java`
- Modify: `core/authorization/src/main/java/pro/deta/orion/auth/check/rule/ApplicationAccessRules.java` only if a
  reusable public admin decision helper is needed; otherwise keep the rule evaluation in the catalog
- Modify: `core/authorization/src/test/java/pro/deta/orion/auth/check/AccessRulesTest.java` only when the rule changes

**Step 1: Build a fixture source and specify authentication**

Create a recording in-memory `OperatorDomainSource` in the catalog test. Add failing tests proving every static and
dynamic command rejects anonymous identities and identities with a null/blank user ID before calling the source.

Require command definitions to have no named parameters or `where` fields in this task.

**Step 2: Specify repository and identity commands**

Add failing tests for:

- `whoami` returning `ObjectValue` with exactly `userId`;
- `/repository ls` returning sorted rows with columns `id`, `name`, `defaultHead`, `refCount`;
- `/repository/<id> show` resolving exact ID, unique visible prefix, or safe exact name and returning `id`, `name`,
  `repositoryName`, `defaultHead`, and `refCount`;
- empty available repositories returning successful empty rows;
- unavailable and failed repository sources returning sanitized `SERVICE_UNAVAILABLE` and `HANDLER_FAILED`;
- ordinary users seeing only repositories allowed by `RepositoryAccessRules.read()` and administrators seeing all;
- a denied exact repository, a prefix that is ambiguous only because of hidden candidates, and completion never
  leaking hidden IDs or names.

Use the backing `repositoryName`, not path ID or display name, for the ACL decision.

**Step 3: Specify organization-local hierarchy**

With fixture data for two organizations containing colliding user and repository names, test:

- `/organization ls` includes only organizations where the caller is a member, can read a repository, or is admin;
- `/organization/<selector>/user ls` exposes only the caller's own principal entry unless admin;
- `/organization/<selector>/repository ls` applies per-repository read authorization;
- exact ID, visible prefix, and safe name resolution are scoped to the current parent organization;
- ambiguity candidates contain visible canonical IDs only;
- unavailable organization data returns `SERVICE_UNAVAILABLE`, not empty rows or missing resource;
- organization rows contain only `id` and `name`; user rows contain `id`, `name`, `principalId`; repository rows use
  the global repository columns.

Do not infer membership from string prefixes in the catalog. Use the fixture/source `organizationId` and
`principalId` fields.

**Step 4: Specify sessions and proxies**

Add fixture tests for `/session ls`, `/session/<selector> show`, and `/proxy ls`:

- admin sees all;
- a session owner sees their sessions;
- a caller with repository read access sees associated sessions and proxies;
- a proxy without an associated repository is visible only to an administrator;
- unrelated entries are filtered before resolution, counts, and ambiguity;
- rows are sorted by canonical ID and have stable fields from the design;
- an absent source returns `SERVICE_UNAVAILABLE`; an unexpected failure returns `HANDLER_FAILED` without the cause.

These tests define adapter-facing semantics without adding a fake production session/proxy implementation.

**Step 5: Specify system commands**

Add failing tests that `/system resource` and `/system/service ls` require application-admin access. Resource output
is an `ObjectValue` ordered as `availableProcessors`, `heapUsedBytes`, `heapCommittedBytes`, `heapMaxBytes`.
Service output is sorted by service ID with columns `id`, `name`, `state`, `computedState`, `terminal`.

**Step 6: Verify RED**

Run outside the sandbox:

```sh
make run-test MODULE=net/git-transport TEST='ReadOnlyDomainCommandCatalogTest'
```

Expected: FAIL because the read-only catalog does not exist.

**Step 7: Implement and verify GREEN**

Use `ScopedResourceResolver` instances backed by the source result. Keep candidate construction and access decisions
in small named methods. Convert source `Unavailable` and `Failed` consistently for list handlers and dynamic catalogs.
Use ordinary loops for filtering and stable sorting.

Repeat the focused command. If authorization-core changes were necessary, also run:

```sh
make run-test MODULE=core/authorization TEST='AccessRulesTest'
```

**Step 8: Commit**

```sh
git add net/git-transport core/authorization
git commit -m "Add ACL-filtered domain commands"
```

### Task 4: Compose the catalog and verify frontend parity

**Files:**

- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalog.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/SshCommandModule.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalogTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/OrionShellTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/render/PlainCommandRenderer.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/terminal/TerminalCommandRenderer.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/render/PlainCommandRendererTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/terminal/TerminalCommandRendererTest.java`
- Modify: `tests/integration-test/src/test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`
- Modify: `README.md`

**Step 1: Specify root-tree composition**

Add failing tests that the legacy root composes `whoami`, `repository`, `organization`, `session`, `proxy`, and
`system` alongside `/auth/key`. Verify the existing aliases retain their exact outputs and authorization.

Bind `DefaultRuntimeMetrics` and `DefaultOperatorDomainSource` as singletons in `SshCommandModule`; inject one
`ReadOnlyDomainCommandCatalog` into the compatibility catalog rather than constructing hidden production instances.

**Step 2: Specify exec and interactive parity**

Extend frontend tests so the same authenticated identity and fixture source produce equal `CommandResult` values for
plain exec and interactive dispatch. Assert no ANSI/prompt is added to exec output and navigator completion exposes
only authorized dynamic resources. Feed structured fields containing CR, LF, tab, backslash, ESC, and other control
characters through both renderers; require escaped one-record-per-line exec output and no source-provided terminal
controls in interactive output. Do not change the legacy multi-line `Message` contract.

Add an end-to-end case for production-backed commands that logs in as a named user, executes `whoami` and an allowed
repository list through SSH exec, and exercises `whoami` from a PTY shell. Preserve Git upload/receive coverage and
prove an arbitrary executable name is still an unknown Orion command rather than an OS process.

**Step 3: Document stable commands and availability**

Update `README.md` with the command tree, stable current fields, ACL behavior, selector order, and the distinction
between empty and unavailable sources. State that organization/session/proxy adapters are enabled by their owning
runtime subsystems rather than fabricated from storage.

**Step 4: Verify focused suites**

Run outside the sandbox:

```sh
make run-test MODULE=core/command \
  TEST='ScopedResourceResolverTest,CommandNavigatorTest,DefaultCommandDispatcherTest,PlainCommandRendererTest,TerminalCommandRendererTest'
make run-test MODULE=net/git-transport \
  TEST='OperatorDomainContractsTest,DefaultOperatorDomainSourceTest,ReadOnlyDomainCommandCatalogTest,LegacySshCommandCatalogTest,OrionShellTest,SshCommandFactoryTest'
make run-test MODULE=tests/integration-test \
  TEST='GitSshTransportEndToEndIT#namedUserCanRunReadOnlyDomainCommandsOverExecAndShell'
```

Require all selected tests to pass. If the exact integration-test locator must be adjusted to the repository's
Failsafe convention, preserve the scenario and record the exact command used.

**Step 5: Run development verification**

Run outside the sandbox:

```sh
mvn verify -Pdev -T 4
```

Require success, or report and reproduce any pre-existing/environmental integration failure on the exact worker base
before claiming it is unrelated.

**Step 6: Commit**

```sh
git add net/git-transport tests/integration-test README.md
git commit -m "Compose read-only SSH domain commands"
```

**Step 7: Prepare orchestrator handoff**

Return the task path, branch/worktree, exact base and head SHAs, commit list, changed-file summary, focused test
results, full verification result, and known unavailable production sources. Do not squash, delete the task node,
cherry-pick to `main`, or clean up until the orchestrator completes review and the user passes the integration gate.

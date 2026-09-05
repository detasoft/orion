# Interactive SSH Query and Output Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add typed, ACL-safe filtering, column projection, bounded pagination, and stable plain, terse, JSON, and
interactive-table output to Orion list commands.

**Architecture:** Replace string-only structured values with one typed result contract, declare query capabilities
on command definitions, and transform successful rows in `DefaultCommandDispatcher` only after handlers have
performed domain authorization. Renderers consume the transformed result and its immutable presentation metadata;
domain handlers remain independent of output formats and Apache Mina SSHD.

**Tech Stack:** Java 21 sealed records, Jackson Core 2.17.2, Apache Mina-independent Orion command core, Apache Mina
SSHD adapters, JUnit 5, AssertJ, Maven.

---

## Non-negotiable behavior

- Replace the old string-cell API everywhere in one task; do not add compatibility constructors, adapters, or a
  second JSON-specific result model.
- Apply ACL filtering in domain handlers before predicates, counts, pagination, completion, or rendering.
- Keep handler row order as the deterministic page order; query processing must not re-sort by projected values.
- Default page size is 100 and maximum page size is 500. Page numbers and sizes are positive integers.
- Preserve exact typed JSON values. Plain/table/terse use escaped text and render null as `null`.
- Reject `format=table` for no-PTY requests with `INVALID_ARGUMENTS` and exit code 2.
- Keep Git exec routing, non-row Orion commands, and credential list semantics unchanged.

### Task 1: Replace string-only structured values with the typed canonical model

**Files:**

- Create: `core/command/src/main/java/pro/deta/orion/command/CommandValue.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandColumn.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/RowOutputFormat.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/RowPage.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandResult.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/CommandModelTest.java`
- Modify all Java producers and tests returned by
  `rg -l 'new CommandResult\\.(Rows|ObjectValue)' --glob '*.java'`

**Step 1: Write failing typed-model tests**

Specify a sealed `CommandValue` with `Text`, `Number`, `BooleanValue`, and singleton-style `NullValue` variants.
Provide explicit factories such as `CommandValue.text(String)`, `number(long)`, `bool(boolean)`, and `nullValue()`.
Use `BigDecimal` for exact numeric storage and canonical JSON spelling.

Specify `CommandColumn(name, type)` with `TEXT`, `NUMBER`, and `BOOLEAN` types. Require non-blank unique column names,
rectangular rows, cell types matching their columns unless the cell is null, immutable copies, and non-null map
keys/values. Specify `RowPage(number, size, matched, OptionalInt next, boolean explicit)` and
`RowOutputFormat.AUTO|PLAIN|TERSE|JSON|TABLE` validation.

The canonical row shape is conceptually:

```java
record Rows(
        List<CommandColumn> columns,
        List<List<CommandValue>> values,
        RowOutputFormat format,
        Optional<RowPage> page) implements CommandResult {}
```

`ObjectValue` becomes `Map<String, CommandValue>`. Do not retain the old `List<String>` or `Map<String, String>`
constructors.

**Step 2: Run RED**

Run outside the sandbox:

```sh
make run-test MODULE=core/command TEST='CommandModelTest'
```

Expected: compilation failure because the typed contracts do not exist.

**Step 3: Implement the contracts and migrate every producer**

Add small local helpers in catalogs/tests for constructing typed text rows. Use numeric values for `refCount`, JVM
byte counts, processor counts, and page fields; booleans for `terminal` and SSH credential `current`; null for absent
optional names/repository associations. Update auditing kind detection without changing audit payloads.

**Step 4: Run GREEN and repository compile coverage**

```sh
make run-test MODULE=core/command TEST='CommandModelTest,DefaultCommandDispatcherTest'
make run-test MODULE=net/git-transport \
  TEST='SshCredentialCommandCatalogTest,ReadOnlyDomainCommandCatalogTest,LegacySshCommandCatalogTest,OrionShellTest,SshCommandFactoryTest'
```

Expected: all selected tests pass with the single typed API.

**Step 5: Commit**

```sh
git add core/command net/git-transport
git commit -m "Add typed command result values"
```

### Task 2: Declare query capabilities and apply them after authorized handlers

**Files:**

- Create: `core/command/src/main/java/pro/deta/orion/command/CommandQuery.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandRowQuery.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandDefinition.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandCompletion.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandNavigator.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/DefaultCommandDispatcher.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/DefaultCommandDispatcherTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/CommandNavigatorTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/audit/AuditingCommandDispatcherTest.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/SshCommandModule.java`

**Step 1: Write failing query tests**

Define `CommandQuery.none()` and a query-enabled contract containing ordered filter/column field names plus known
enum values. Replace `CommandDefinition.allowedWhereFields` and `CommandCompletion.whereValues` with this one
canonical metadata source. Update all command definitions in the repository; non-list commands use
`CommandQuery.none()`.

Add dispatcher tests for:

- conjunctions of `=` and `!=` over text, number, boolean, enum, and null;
- quoted and escaped predicate values already produced by `CommandLineParser`;
- reserved `null`, invalid numeric/boolean/enum literals, and unknown fields;
- `columns=id,state`, including order, duplicates, empty components, and unknown columns;
- default/explicit page, maximum size, integer overflow, empty page, and beyond-end page;
- `format=plain|terse|json|table`, including no-PTY table rejection;
- query arguments rejected for definitions with `CommandQuery.none()`;
- handler failures returned unchanged rather than queried.

Use an authorized handler fixture containing at least one row that represents a resource the fixture removed before
returning. Assert matched count, next page, and empty-page behavior are calculated only from returned rows.

**Step 2: Run RED**

```sh
make run-test MODULE=core/command \
  TEST='DefaultCommandDispatcherTest,CommandNavigatorTest,AuditingCommandDispatcherTest'
```

Expected: failures because definitions have no query contract and dispatcher returns handler rows unchanged.

**Step 3: Implement the generic query stage**

`CommandRowQuery.apply(...)` receives the authorized `Rows`, arguments, query metadata, and
`CommandPresentation`. It validates query arguments, evaluates predicates before projection, calculates `matched`,
slices by checked `long` offsets, and returns either transformed `Rows` or `CommandResult.Failure` with
`INVALID_ARGUMENTS`.

In `DefaultCommandDispatcher`, invoke this stage only after a successful handler returns rows. Extend argument
validation and audit classification from `CommandQuery`; reserved query parameters are allowed only when query is
enabled. Bind the one stateless query object in `SshCommandModule` and use the new dispatcher constructor everywhere.

**Step 4: Complete metadata-driven completion**

Teach `CommandNavigator` to complete `columns=`, including the fragment after the last comma, built-in format
values, query field names after `where`, and known enum values for `=` and `!=`. Keep results deterministic and do
not consult domain rows.

**Step 5: Run GREEN**

Repeat the focused command-core tests and require all selected tests to pass.

**Step 6: Commit**

```sh
git add core/command net/git-transport/src/main/java/pro/deta/orion/transport/git/command/SshCommandModule.java
git commit -m "Apply authorized command row queries"
```

### Task 3: Add stable terse and typed JSON rendering

**Files:**

- Modify: `core/command/pom.xml`
- Create: `core/command/src/main/java/pro/deta/orion/command/render/TerseCommandRenderer.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/render/JsonCommandRenderer.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/render/PlainCommandRenderer.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/terminal/TerminalCommandRenderer.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/render/PlainCommandRendererTest.java`
- Create: `core/command/src/test/java/pro/deta/orion/command/render/TerseCommandRendererTest.java`
- Create: `core/command/src/test/java/pro/deta/orion/command/render/JsonCommandRendererTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/terminal/TerminalCommandRendererTest.java`

**Step 1: Write failing renderer tests**

Test exact bytes for:

- unchanged escaped TSV headers and rows for unqueried/plain results;
- headerless escaped TSV for terse results;
- typed JSON in the stable envelope from the design, with declared key order and one trailing newline;
- quotes, backslashes, CR/LF/tab, ESC, C0/C1 controls, Unicode, and null;
- conditional plain/terse/table pagination metadata and unconditional JSON pagination metadata;
- AUTO/table alignment at fitting terminal widths and plain fallback at narrow widths;
- explicit plain/terse/JSON equality between terminal and no-PTY renderers.

**Step 2: Run RED**

```sh
make run-test MODULE=core/command \
  TEST='PlainCommandRendererTest,TerseCommandRendererTest,JsonCommandRendererTest,TerminalCommandRendererTest'
```

Expected: compilation failures for the new renderers and typed output dispatch.

**Step 3: Implement renderer selection**

Add `com.fasterxml.jackson.core:jackson-core:${jackson.version}` to `core/command`. Use `JsonGenerator` for
standards-compliant escaping and explicit typed writes. Keep `PlainCommandRenderer` as the frontend entry point that
selects stable plain, terse, or JSON for row results. `TerminalCommandRenderer` uses table layout only for AUTO or
TABLE and delegates all explicit automation formats to the same frontend entry point.

Render metadata as:

```text
# page=1 page-size=2 matched=3 next-page=2
```

Omit the line for an implicit complete first page. Use `next-page=null` when metadata is otherwise required and
there is no continuation.

**Step 4: Run GREEN**

Repeat the renderer suite and require all selected tests to pass.

**Step 5: Commit**

```sh
git add core/command
git commit -m "Add stable command automation renderers"
```

### Task 4: Enable query metadata on ACL-filtered domain list commands

**Files:**

- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/ReadOnlyDomainCommandCatalog.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/ReadOnlyDomainCommandCatalogTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalogTest.java`

**Step 1: Write failing domain query tests**

Enable query metadata on repository, organization, organization-user, organization-repository, session, proxy, and
system-service `ls` definitions. Keep `whoami`, `show`, `system resource`, legacy aliases, and credential management
non-queryable.

Test happy paths and edge cases across real catalog results:

- repository numeric `refCount`, session/proxy nullable associations, and service boolean `terminal`;
- session and proxy enum/state completion from values known by the command contract;
- multiple predicates, selected column order, pagination boundaries, and deterministic canonical-ID order;
- ordinary users' hidden rows absent from matches, counts, next page, and errors while admin sees the full set;
- unavailable and failed source results unchanged by query arguments.

**Step 2: Run RED**

```sh
make run-test MODULE=net/git-transport \
  TEST='ReadOnlyDomainCommandCatalogTest,LegacySshCommandCatalogTest'
```

Expected: query requests fail validation because catalog definitions have no query metadata.

**Step 3: Implement catalog metadata and typed rows**

Build each list definition from its exact ordered column schema. Enumerate only stable state values already defined
by the domain contract; leave free-form text fields without value completion. Continue to construct rows only after
the existing source availability and per-item ACL checks.

**Step 4: Run GREEN**

Repeat the focused catalog tests and require all selected tests to pass.

**Step 5: Commit**

```sh
git add net/git-transport/src/main/java/pro/deta/orion/transport/git/command \
  net/git-transport/src/test/java/pro/deta/orion/transport/git/command
git commit -m "Enable ACL-safe SSH list queries"
```

### Task 5: Verify SSH frontends and document the stable automation contract

**Files:**

- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/OrionShellTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`
- Modify: `README.md`

**Step 1: Add frontend and integration coverage**

Test the same explicit JSON and terse list command through interactive dispatch and SSH exec, with byte-identical
payload and no ANSI output. Test invalid no-PTY table format exits with code 2. Keep existing arbitrary-command and
Git upload/receive routing assertions.

Extend the live named-user SSH scenario with an ACL-visible repository query using selected columns and JSON, plus
one invalid predicate that exits 2 without exposing hidden values.

**Step 2: Update documentation**

Document query syntax, types, reserved null, defaults and limits, output formats, exact JSON/page envelope,
conditional plain metadata, ACL-before-query behavior, and examples for exec automation and interactive use.

**Step 3: Run focused verification**

Run outside the sandbox:

```sh
make run-test MODULE=core/command \
  TEST='CommandModelTest,CommandLineParserTest,CommandNavigatorTest,DefaultCommandDispatcherTest,AuditingCommandDispatcherTest,PlainCommandRendererTest,TerseCommandRendererTest,JsonCommandRendererTest,TerminalCommandRendererTest'
make run-test MODULE=net/git-transport \
  TEST='SshCredentialCommandCatalogTest,ReadOnlyDomainCommandCatalogTest,LegacySshCommandCatalogTest,OrionShellTest,SshCommandFactoryTest'
make run-test MODULE=tests/integration-test \
  TEST='GitSshTransportEndToEndIT#namedUserCanQueryReadOnlyDomainCommandsOverExecAndShell'
```

Expected: all selected tests pass.

**Step 4: Run development verification**

```sh
mvn verify -Pdev -T 4
```

Expected: `BUILD SUCCESS`.

**Step 5: Commit**

```sh
git add README.md net/git-transport/src/test tests/integration-test/src/integration-test
git commit -m "Verify SSH query automation behavior"
```

### Task 6: Review, squash, transfer, and clean up the task

**Files:**

- Read: `docs/reviews/RULES.md`
- Delete: `docs/plans/current-work/interactive-ssh-shell/query-and-output/TASK.md`
- Modify: `docs/plans/current-work/interactive-ssh-shell/TASK.md`

**Step 1: Review the complete diff**

Use `superpowers:requesting-code-review`, apply `docs/reviews/RULES.md`, fix blocking findings, and rerun affected
focused tests. Run `git diff --check` and confirm the worktree contains no unrelated changes.

**Step 2: Verify before completion**

Use `superpowers:verification-before-completion` and rerun `mvn verify -Pdev -T 4` outside the sandbox. Record the
exact base/head SHAs and verification output.

**Step 3: Create the single task commit**

Squash every commit unique to the task branch, including claim/design/plan commits and review fixes. In the same
commit, delete the completed leaf directory and remove its parent link. Use exactly:

```text
Add interactive SSH queries and automation output [task: current-work/interactive-ssh-shell/query-and-output]
```

**Step 4: Transfer to main and run post-commit tests**

Cherry-pick the squashed commit onto current `main`, never merge. Run `make test` outside the sandbox. If a task bug
causes failure, fix it in a follow-up commit with the exact same subject so it can be squashed.

**Step 5: Finish the branch**

Use `superpowers:finishing-a-development-branch`. Remove the completed worktree and delete its branch only after the
main cherry-pick, successful post-commit tests, and clean main status. Confirm `git worktree list` no longer contains
the task worktree before reporting completion.

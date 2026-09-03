# Focused Make Test Target Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a validated `make run-test` shortcut that accepts a Maven module and test locator in named or positional form.

**Architecture:** Parse positional values only when `run-test` is the first Make goal, and handle otherwise unknown positional values with a scoped fallback rule. Reject values that collide with another Make goal, resolve both interfaces to one quoted Maven command, and document the named interface as the required agent workflow.

**Tech Stack:** GNU Make, Maven Surefire, Markdown

---

### Task 1: Establish the missing target

**Files:**
- Modify: `Makefile`

**Step 1: Verify the named interface is absent**

Run:

```shell
make -n run-test MODULE=core/common TEST=LogInitializerTest
```

Expected: FAIL because there is no `run-test` rule.

**Step 2: Verify the positional interface is absent**

Run:

```shell
make -n run-test core/common 'LogInitializerTest#initializesLogging'
```

Expected: FAIL because there is no `run-test` rule.

### Task 2: Implement and validate the Make interface

**Files:**
- Modify: `Makefile`
- Create: `tests/test-support/src/test/java/pro/deta/orion/makefile/FocusedTestMakefileTest.java`

**Step 1: Add a failing Makefile contract test**

Cover equivalent named and positional Maven arguments, nested-class locators,
Maven artifact selectors, invalid and conflicting argument combinations, and
an unrelated unknown goal. Override `MAVEN` with an argument printer so the
tests exercise Make without recursively invoking Maven.

**Step 2: Run the contract test and verify it fails**

Run outside the sandbox:

```shell
mvn test -Pdev -T 4 -q -pl tests/test-support -am \
  -Dtest=FocusedTestMakefileTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `run-test` has no rule.

**Step 3: Add argument resolution**

When `run-test` is the first goal, collect subsequent goals as positional
arguments and enable a fallback no-op rule for otherwise unknown goals. Reject
values matching existing top-level Make goals as ambiguous. Resolve two
positional arguments to the module and test locator; otherwise use `MODULE` and
`TEST`.

**Step 4: Add strict validation and the Maven recipe**

Accept exactly one of these combinations:

```text
no positional arguments + non-empty MODULE and TEST
two positional arguments + empty MODULE and TEST
```

For any other combination, print both supported invocations and exit nonzero.
For valid input, run:

```make
$(MAVEN) test -Pdev -T 4 -q -pl '$(RUN_TEST_MODULE)' -am \
    -Dtest='$(RUN_TEST_LOCATOR)' \
    -Dsurefire.failIfNoSpecifiedTests=false
```

**Step 5: Run the contract test and verify it passes**

Run the command from step 2.

Expected: PASS.

**Step 6: Verify validation without invoking Maven**

Run each command and expect a nonzero exit plus usage text:

```shell
make run-test
make run-test MODULE=core/common
make run-test core/common LogInitializerTest extra
make run-test core/common LogInitializerTest MODULE=core/common TEST=LogInitializerTest
make run-test core/common test
```

**Step 7: Verify dry-run command construction**

Run:

```shell
make -n run-test MODULE=core/common TEST='LogInitializerTest#initializesLogging'
make -n run-test core/common 'LogInitializerTest#initializesLogging'
```

Expected: both print equivalent Maven commands with the standard focused-test
flags.

**Step 8: Preserve ordinary unknown-goal behavior**

Run:

```shell
make definitely-not-a-target
```

Expected: FAIL with a normal no-rule error.

### Task 3: Document and exercise the workflow

**Files:**
- Modify: `AGENTS.md`

**Step 1: Replace focused-test command guidance**

Tell agents to use:

```shell
make run-test MODULE=<module> TEST='<test-locator>'
```

State that this target is allowed without asking for confirmation and keep
`make test` as the full-project test command.

**Step 2: Run a focused test through each interface**

Run outside the sandbox:

```shell
make run-test MODULE=core/common TEST=LogInitializerTest
make run-test core/common LogInitializerTest
```

Expected: PASS for both invocations.

**Step 3: Inspect the final change**

Run:

```shell
git diff --check
git diff -- Makefile AGENTS.md docs/plans/2026-09-03-focused-make-test-target.md
git status --short
```

Expected: no whitespace errors; only the intended implementation, guidance,
and plan changes are present.

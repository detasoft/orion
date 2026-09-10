# AgentD Local Session Launch Implementation Plan

**Goal:** Add a launch-only `agentd terminal start` command that starts a real
`session-host` through `NativeRuntime` without an Orion server.

**Architecture:** Add an early local-command branch to `AgentdMain` while
preserving the existing daemon invocation. A small local launcher parses only
the inputs needed to build `SessionSpec`, invokes `NativeRuntime`, renders its
standard result, and exits without owning the launched host.

**Tech Stack:** Java 21, existing AgentD runtime/session contracts, JUnit 5,
AssertJ, packaged Rust `session-host` test artifact.

---

## Task 1: Route and parse the local launch command

**Files:**

- Modify `agentd/src/main/java/pro/deta/orion/agentd/AgentdMain.java`.
- Modify `agentd/src/test/java/pro/deta/orion/agentd/AgentdMainTest.java`.
- Create `agentd/src/main/java/pro/deta/orion/agentd/terminal/LocalSessionLauncher.java`.
- Create `agentd/src/test/java/pro/deta/orion/agentd/terminal/LocalSessionLauncherTest.java`.

Add failing tests that establish these behaviors:

- `terminal start` is routed without reading stdin or constructing the daemon;
- existing daemon arguments retain their current behavior;
- required paths and the command after `--` are parsed;
- omitted identifiers, working directory, and terminal environment receive the
  approved defaults; and
- missing values, unknown options, an empty command, or another terminal
  subcommand return usage failure without launching anything.

Implement the smallest router and immutable parsed request needed by those
tests. Keep daemon parsing and permit handling in the existing branch.

Run:

```text
make run-test MODULE=agentd TEST='AgentdMainTest,LocalSessionLauncherTest'
```

Expected result: both test classes pass after their new cases first fail for the
missing command.

## Task 2: Launch through the existing native runtime

**Files:**

- Modify `agentd/src/main/java/pro/deta/orion/agentd/terminal/LocalSessionLauncher.java`.
- Modify `agentd/src/test/java/pro/deta/orion/agentd/terminal/LocalSessionLauncherTest.java`.
- Create `agentd/src/test/java/pro/deta/orion/agentd/terminal/LocalSessionLaunchLivePeerTest.java`.

Add failing tests that require the launcher to build the expected `SessionSpec`,
call a supplied `SessionRuntime`, print the ID and normalized directory for
`Started`, and map `Failed` to a bounded diagnostic and exit code 1. Cover one
invalid-input case and one runtime failure without exposing command contents.

Implement composition with `NativeRuntime` using explicit local-command
timeouts: ten seconds for initialization and two seconds each for control and
cleanup. Truncate rendered failure details to 512 characters. Do not add a new
runtime interface, lifecycle service, background thread, cursor, or server
dependency.

Add a live-peer case that invokes the command with the packaged native binary,
waits for successful handoff, verifies the manifest/control endpoint, and proves
the host remains alive after the command returns. Terminate the fixture through
the existing native control client during test cleanup.

Run:

```text
make run-test MODULE=agentd \
  TEST='AgentdMainTest,LocalSessionLauncherTest,LocalSessionLaunchLivePeerTest'
mvn test -Pdev -T 4
```

Expected result: focused launch tests and the complete Maven/JVM pre-commit
suite pass.

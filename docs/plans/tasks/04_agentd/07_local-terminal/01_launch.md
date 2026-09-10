# Launch a Local Session Host from AgentD

Status: todo
Depends on: completed native runtime and local control support `2b08b1fd` and
durable native start outcomes `435e9ae1`

Add `agentd terminal start` as a launch-only local command. It starts a real
`session-host` through `NativeRuntime`, waits for durable handoff, prints the
session identity and directory, and exits without stopping the host.

## Scope

- Route `terminal start` before daemon configuration or launch-permit handling.
- Accept the host executable, state directory, optional session identity and
  working directory, followed by the child command after `--`.
- Use fixed non-interactive PTY defaults and existing environment values where
  applicable; do not acquire or modify the caller's terminal.
- Reuse `SessionSpec`, `NativeRuntime`, and `SessionLaunchResult` without a
  second launch abstraction or server runtime.
- Report invalid input separately from runtime launch failure.

## Acceptance

- A real packaged `session-host` reaches durable handoff when started through
  the AgentD command and remains alive after AgentD exits.
- Local launch does not read a launch permit, acquire the daemon process lock,
  or initialize HTTP/2 transport.
- Successful output contains the session ID and normalized session directory;
  failures have a non-zero exit code and bounded diagnostics.
- Existing daemon invocation and behavior remain unchanged.

## Boundary

Interactive attach, journal rendering, input, resize, signals, termination, and
journal acknowledgement belong to the following child task.

---

## AgentD Local Session Launch Design

Status: approved on 2026-09-10.

### Goal

Provide the earliest useful local path from AgentD to a real `session-host`:

```text
agentd terminal start --session-host PATH --state-dir PATH [options] -- COMMAND...
```

The command starts the host, waits until its manifest, journal, and control
endpoint establish durable handoff, prints the session identity and directory,
and exits. It does not attach the caller's terminal.

### Design

`AgentdMain` recognizes `terminal start` before daemon argument parsing. All
other invocations retain the existing daemon behavior, so provisioning and
server launch remain unchanged. Local mode never reads stdin as a launch permit,
constructs `Agent`, acquires the daemon process lock, or initializes Jetty.

The local command parses `--session-host`, `--state-dir`, optional
`--session-id`, and optional `--cwd`, followed by the child command after `--`.
It derives the sessions directory from the state directory, generates omitted
session and start-command identities, uses a fixed 80 by 24 PTY size, reads
`TERM` and `COLORTERM` when valid, and otherwise uses `xterm-256color` without a
color-terminal value. Sandbox policy and arbitrary child environment are out of
scope for this first slice.

The command builds the existing `SessionSpec` and calls the existing
`NativeRuntime`. A `Started` result is printed as one stable line containing the
session ID and normalized directory. Invalid CLI input returns exit code 2;
launch and handoff failures return exit code 1 with the existing failure kind
and a detail truncated to 512 characters. The launched host remains independent
when the Java process exits.

### Verification

Unit tests cover routing without stdin access, parsing/defaults, successful
result rendering, and validation or launch failures. A live-peer test uses the
packaged native executable to prove durable handoff and continued host liveness
after the AgentD command returns.

### Deferred Work

Interactive attachment, raw-terminal ownership, journal replay/following,
input, resize, manual sequencing, signals, termination, and acknowledgement
remain in the broader local-terminal design.

---

## AgentD Local Session Launch Implementation Plan

**Goal:** Add a launch-only `agentd terminal start` command that starts a real
`session-host` through `NativeRuntime` without an Orion server.

**Architecture:** Add an early local-command branch to `AgentdMain` while
preserving the existing daemon invocation. A small local launcher parses only
the inputs needed to build `SessionSpec`, invokes `NativeRuntime`, renders its
standard result, and exits without owning the launched host.

**Tech Stack:** Java 21, existing AgentD runtime/session contracts, JUnit 5,
AssertJ, packaged Rust `session-host` test artifact.

---

### Task 1: Route and parse the local launch command

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

### Task 2: Launch through the existing native runtime

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

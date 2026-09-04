# AgentD Local Interactive Terminal Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add explicit `daemon` and local POSIX `terminal` modes to the AgentD executable so developers can
launch or attach to `session-host` through production journal and control paths without an Orion server.

**Architecture:** Keep `AgentdMain` as a small command router. The daemon branch retains server bootstrap,
while the terminal branch composes `NativeRuntime`, session discovery/control, bounded journal following, and a
POSIX terminal adapter without constructing `Agent` or Jetty. Reuse the completed command-orchestration and
journal-sync seams for operation recovery and `ACK_JOURNAL`; do not create a second command ledger or cursor.

**Tech Stack:** Java 21, Agent protocol CBOR, AgentD journal/control components, JLine 3 POSIX terminal support,
JUnit 5, AssertJ, native Rust `session-host` test artifact.

---

## Prerequisite checkpoint

Do this work only after these task nodes are complete and their task worktrees have been integrated:

- `docs/plans/current-work/agentd/journal-sync/TASK.md`
- `docs/plans/current-work/agentd/command-orchestration/TASK.md`
- the native control-journal idempotency and start-outcome contracts referenced by those nodes

Before editing, read the final shared APIs in `agentd/journal`, `agentd/session`, and the command-orchestration
package. The class names below follow the approved prerequisite plans. If integration renamed a seam, use the
integrated equivalent rather than adding an adapter whose only purpose is to preserve this plan's provisional
name. Read every `@AiRule` class comment in a class before changing it.

Execute the task in a dedicated worktree. Claim
`docs/plans/current-work/agentd/local-terminal/TASK.md` first and commit the claim without running tests.

### Task 1: Make the AgentD top-level mode explicit

**Files:**

- Modify: `agentd/src/main/java/pro/deta/orion/agentd/AgentdMain.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/AgentdMainTest.java`
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteAgentdProvisioner.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/RemoteAgentdProvisionerTest.java`

**Step 1: Add failing top-level routing tests**

Change `AgentdMainTest` so the existing daemon cases pass `daemon` before all options. Add cases asserting:

```java
assertThat(run(new String[]{"--server", "https://agent.test"}, forbiddenInput, daemon, terminal))
        .isEqualTo(2);
assertThat(run(new String[]{"daemon", "--help"}, forbiddenInput, daemon, terminal)).isZero();
assertThat(run(new String[]{"terminal", "--help"}, forbiddenInput, daemon, terminal)).isZero();
```

The old subcommand-free syntax must print top-level usage and must not consume the launch permit. A terminal
request must be delegated without reading stdin in the daemon permit reader. Keep the two launch seams narrow
and package-private for tests.

Update `RemoteAgentdProvisionerTest` to expect `daemon` as the first generated argument.

**Step 2: Run focused tests and verify the new expectations fail**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.AgentdMainTest'
make run-test MODULE=agent-provisioning TEST='pro.deta.orion.provisioning.RemoteAgentdProvisionerTest'
```

Expected: FAIL because `AgentdMain` does not dispatch subcommands and provisioning omits `daemon`.

**Step 3: Implement the command router and provisioning migration**

Give `AgentdMain` top-level usage shaped like:

```text
Usage: agentd COMMAND [options]

Commands:
  daemon    connect this machine to an Orion server
  terminal  launch or attach to a local session-host
```

Dispatch on the first argument and pass only the remaining arguments to the selected mode. Keep the existing
daemon parse, permit, redaction, shutdown-hook, and exit-code behavior under `daemon`. Reject a missing or
unknown command with exit code 2. `agentd --help`, `agentd daemon --help`, and `agentd terminal --help` must all
return zero and read no permit.

Prepend `daemon` in `RemoteAgentdProvisioner.agentdArguments(...)`. Do not retain an undocumented compatibility
alias for the old invocation.

**Step 4: Run both focused test classes**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.AgentdMainTest'
make run-test MODULE=agent-provisioning TEST='pro.deta.orion.provisioning.RemoteAgentdProvisionerTest'
```

Expected: PASS.

**Step 5: Commit**

```text
git add agentd/src agent-provisioning/src
git commit -m "Route AgentD through explicit daemon and terminal modes"
```

### Task 2: Define and validate the terminal CLI contract

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalOptions.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalMain.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalOptionsTest.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalMainTest.java`

**Step 1: Write failing parser tests**

Freeze these forms:

```text
agentd terminal start --session-host PATH --sessions-dir PATH [--session-id ID]
    [--cwd PATH] [--term VALUE] [--colorterm VALUE]
    [--sandbox-policy PATH] [--sandbox-unavailable fail|run-unsandboxed]
    [--ack-journal] -- COMMAND...

agentd terminal attach --session-dir PATH [--ack-journal]
```

Test a minimal start, all optional start fields, and attach. Assert that `ackJournal` is false when omitted and
true only when the flag is present. Use the current directory as the default `--cwd`; use the attached terminal's
environment for `TERM` and optional `COLORTERM`, with `xterm-256color` only when `TERM` is absent.

Reject unknown/duplicate options, missing values, empty commands, positional data before `--`, attach-only
options on start, start-only options on attach, non-POSIX platforms, invalid IDs, non-directory workspaces, and
non-executable host paths. Keep terminal dimensions out of `TerminalOptions`; they are sampled from the acquired
TTY immediately before launch.

Generate absent identifiers in the forms `local-session-<UUID>` and `local-start-<UUID>`, both validated by the
shared `SessionId` and `CommandId` constructors.

**Step 2: Run the parser tests and verify they fail**

Run outside the sandbox:

```text
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.terminal.TerminalOptionsTest,pro.deta.orion.agentd.terminal.TerminalMainTest'
```

Expected: FAIL because the terminal package does not exist.

**Step 3: Implement immutable options and help**

Use a sealed options model rather than a nullable bag:

```java
sealed interface TerminalOptions {
    boolean ackJournal();

    record Start(
            Path sessionHost,
            Path sessionsDirectory,
            SessionId sessionId,
            CommandId startCommandId,
            List<String> command,
            Path workingDirectory,
            String terminalType,
            Optional<String> colorTerminal,
            SessionSpec.Sandbox sandbox,
            boolean ackJournal
    ) implements TerminalOptions { }

    record Attach(Path sessionDirectory, boolean ackJournal) implements TerminalOptions { }
}
```

Copy collections and normalize paths in compact constructors. `TerminalMain` owns terminal-specific help and
maps parse/validation failures to exit code 2 without acquiring raw mode. Keep `--ack-journal` description
explicit: it may authorize native retention without preserving a local server replica.

**Step 4: Run the terminal parser tests**

Run outside the sandbox:

```text
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.terminal.TerminalOptionsTest,pro.deta.orion.agentd.terminal.TerminalMainTest'
```

Expected: PASS.

**Step 5: Commit**

```text
git add agentd/src/main/java/pro/deta/orion/agentd/terminal \
  agentd/src/test/java/pro/deta/orion/agentd/terminal
git commit -m "Define AgentD local terminal commands"
```

### Task 3: Isolate POSIX terminal ownership

**Files:**

- Modify: `pom.xml`
- Modify: `agentd/pom.xml`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalDevice.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/JlinePosixTerminal.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/JlinePosixTerminalTest.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/FakeTerminalDevice.java`

**Step 1: Write failing terminal-resource tests**

Introduce the smallest orchestration-facing contract:

```java
interface TerminalDevice extends AutoCloseable {
    InputStream input();
    OutputStream output();
    TerminalSize size();
    void onResize(Runnable listener);
    void enterRawMode();
    @Override void close();
}
```

`TerminalSize` validates dimensions in `1..65535`. Tests must prove raw mode is entered once, the original
attributes are restored once even after repeated close, resize callbacks observe the latest size, and acquisition
failure leaves no half-registered shutdown hook. Do not require the Surefire process itself to own a TTY; wrap a
fake JLine terminal/backend.

**Step 2: Run the focused test and verify it fails**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.terminal.JlinePosixTerminalTest'
```

Expected: FAIL because the terminal abstraction and JLine dependency do not exist.

**Step 3: Add JLine and implement the adapter**

Add the root property and direct AgentD dependency:

```xml
<jline.version>3.24.1</jline.version>

<dependency>
    <groupId>org.jline</groupId>
    <artifactId>jline</artifactId>
    <version>${jline.version}</version>
</dependency>
```

Use JLine's system terminal, raw-mode attributes, and `WINCH` signal callback. Reject dumb/non-system terminals
for interactive execution instead of silently treating redirected stdin as a TTY.

Register a restoration hook only after terminal acquisition succeeds. Normal close restores attributes and
removes the hook; a JVM shutdown racing with close remains idempotent. Do not expose JLine types outside
`JlinePosixTerminal`.

**Step 4: Run terminal-resource tests**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.terminal.JlinePosixTerminalTest'
```

Expected: PASS.

**Step 5: Commit**

```text
git add pom.xml agentd/pom.xml agentd/src/main/java/pro/deta/orion/agentd/terminal \
  agentd/src/test/java/pro/deta/orion/agentd/terminal
git commit -m "Add POSIX terminal ownership to AgentD"
```

### Task 4: Resolve launch and attach targets

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalTarget.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalTargetResolver.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalTargetResolverTest.java`

**Step 1: Write failing start and attach tests**

For start, verify the resolver samples the terminal size, builds an equivalent `SessionSpec`, calls
`NativeRuntime.launch(...)`, and returns the launched directory only for `SessionLaunchResult.Started`.

For attach, create a valid manifest fixture and assert the resolver:

- reads it through `JsonSessionManifestReader`;
- verifies the directory's journal is readable;
- calls `STATUS` through the shared host/control probe;
- requires the POSIX Unix-domain endpoint and a live host; and
- returns the manifest session ID and exact control endpoint.

Cover a start failure, malformed manifest, session ID/directory mismatch, missing journal, unsupported named
pipe, unreachable host, and an already exited child whose journal remains replayable. The last case may attach
for replay but must not accept controls.

**Step 2: Run the resolver test and verify it fails**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.terminal.TerminalTargetResolverTest'
```

Expected: FAIL because the resolver does not exist.

**Step 3: Implement one shared target model**

Use one result after either path:

```java
record TerminalTarget(
        SessionId sessionId,
        Path sessionDirectory,
        ControlEndpoint controlEndpoint,
        boolean acceptsControl
) { }
```

Map `TerminalOptions.Start` into `SessionSpec` with the sampled dimensions and existing workspace/sandbox
types. Do not launch `Agent`, acquire the AgentD process lock, or create any server transport. Attach must inspect
one explicit directory rather than scanning or claiming all local sessions.

**Step 4: Run resolver and existing runtime/discovery tests**

Run outside the sandbox:

```text
make run-test MODULE=agentd \
  TEST='TerminalTargetResolverTest,NativeRuntimeTest,SessionDiscoveryTest'
```

Expected: PASS.

**Step 5: Commit**

```text
git add agentd/src/main/java/pro/deta/orion/agentd/terminal \
  agentd/src/test/java/pro/deta/orion/agentd/terminal
git commit -m "Launch and attach local AgentD terminal sessions"
```

### Task 5: Replay and follow terminal output from the journal

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalJournalFollower.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalJournalFollowerTest.java`

**Step 1: Write failing follower tests**

Drive the follower with the shared `SessionJournalReader` and a monitor factory. Cover:

- replay from an empty cursor through the stable retained tail;
- `PTY_OUTPUT` payloads written byte-for-byte and flushed in journal order;
- unknown records advancing the cursor without producing terminal bytes;
- live records after `INCOMPLETE_TAIL`, page limits, rotation, file replacement, watch overflow, and timeout;
- no duplicate bytes when a disposable read position is invalidated and scanning resumes by EventId;
- `PROCESS_EXITED` delivered only after all preceding output;
- output failure, a journal issue, and a required-history gap stopping the follower with a typed result; and
- no acknowledgement calls anywhere in the default follower path.

Use small page limits in tests. Do not duplicate compressed-segment or CBOR framing tests already owned by
`FileSystemSessionJournalReaderTest`.

**Step 2: Run the follower test and verify it fails**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.terminal.TerminalJournalFollowerTest'
```

Expected: FAIL because the follower does not exist.

**Step 3: Implement bounded journal consumption**

Keep an in-memory EventId cursor and disposable `JournalReadPosition`. Decode known payloads with the shared
`SessionEventCodec`; pass each record to the command-orchestration journal observer before moving the cursor.
This supplies the recovered lifecycle and operation-sequence prefix used by the input lane. Unknown records are
not re-encoded.

Treat `INCOMPLETE_TAIL` as a wait boundary. Treat `PAGE_LIMIT` as immediate continued work before awaiting a
filesystem trigger. On gap or corrupt complete data, return a typed failure containing bounded location/detail
and never guess a replacement cursor.

**Step 4: Run follower and journal-reader tests**

Run outside the sandbox:

```text
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.terminal.TerminalJournalFollowerTest,pro.deta.orion.agentd.journal.FileSystemSessionJournalReaderTest'
```

Expected: PASS.

**Step 5: Commit**

```text
git add agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalJournalFollower.java \
  agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalJournalFollowerTest.java
git commit -m "Follow local session output from the durable journal"
```

### Task 6: Forward interactive input and resize controls

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalInput.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalSession.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalInputTest.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalSessionTest.java`

**Step 1: Write failing input and coordination tests**

Test input chunking at the shared protocol limit and escape parsing across arbitrary read boundaries:

```text
Ctrl-] d       detach locally and send neither byte
Ctrl-] Ctrl-]  send one literal Ctrl-]
Ctrl-] X       send both bytes when X is any other byte
```

Verify ordinary bytes, including Ctrl-C, remain `INPUT` payload. Verify the initial terminal size is used for
start and each changed size becomes an ordered `RESIZE`; repeated notifications with the same dimensions do
nothing.

Use the completed command scheduler/lane from the prerequisite task. Assert every generated message has a fresh
CommandId, exact bytes from `AgentProtocolCodec.encode(...)`, and a recovered monotonic operation sequence.
Controls must wait until journal catch-up establishes a safe sequence. A gap or corrupt suffix keeps output
diagnostics available but sends no control.

Cover detach, EOF, interrupted reads, rejected control, ambiguous delivery, `PROCESS_EXITED`, and concurrent
resize/input. Assert close never sends `TERMINATE` and always closes the terminal device once.

**Step 2: Run terminal session tests and verify they fail**

Run outside the sandbox:

```text
make run-test MODULE=agentd \
  TEST='pro.deta.orion.agentd.terminal.TerminalInputTest,pro.deta.orion.agentd.terminal.TerminalSessionTest'
```

Expected: FAIL because the input parser and session coordinator do not exist.

**Step 3: Implement the input lane and lifecycle coordinator**

Run journal following and terminal input in two owned virtual threads. Feed all input and resize messages to one
bounded serial command lane so their operation sequence matches delivery order. Stop admitting input after
detach, exit, unsafe recovery, or control failure. The journal remains the source of durable command results and
process exit; a direct native acknowledgement is delivery evidence, not terminal output.

Coordinate completion through structured close state rather than `System.exit` inside worker threads. Normal
detach returns zero. A recorded process exit returns the child's exit code after journal drain. Internal errors
return one; CLI errors remain two.

**Step 4: Run terminal and prerequisite orchestration tests**

Run outside the sandbox:

```text
make run-test MODULE=agentd \
  TEST='TerminalInputTest,TerminalSessionTest,SessionCommandSchedulerTest,SessionJournalObserverTest'
```

Expected: PASS. If the prerequisite integrated equivalent test names differ, include those exact classes instead.

**Step 5: Commit**

```text
git add agentd/src/main/java/pro/deta/orion/agentd/terminal \
  agentd/src/test/java/pro/deta/orion/agentd/terminal
git commit -m "Forward local terminal input through session control"
```

### Task 7: Add explicit journal acknowledgement and assemble terminal mode

**Files:**

- Modify: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalJournalFollower.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/terminal/TerminalMain.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalJournalFollowerTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/terminal/TerminalMainTest.java`

**Step 1: Write failing ACK and assembly tests**

Verify no `ACK_JOURNAL` is sent for default start or attach. With `--ack-journal`, assert exactly one monotonic
watermark is sent after each fully consumed page and only after its terminal output write succeeds. Do not ACK
an incomplete tail, gap, issue, failed output, or a page whose records were only partially observed.

Cover repeated/lower native acknowledgement responses, a rejected watermark, and delivery failure. After the
first ACK failure, report it once, disable later ACK attempts, and continue journal output until detach or process
exit. Never terminate the host because acknowledgement failed.

Add an assembly test proving terminal mode constructs no `Agent`, `AgentLaunchContext`, launch permit reader,
Jetty transport, or listening server.

**Step 2: Run ACK and main tests and verify they fail**

Run outside the sandbox:

```text
make run-test MODULE=agentd \
  TEST='TerminalJournalFollowerTest,TerminalMainTest,AgentdMainTest'
```

Expected: FAIL because terminal assembly and optional ACK are incomplete.

**Step 3: Wire terminal components and ACK policy**

Pass one acknowledgement policy from parsed options into the follower. Reuse the shared non-journaled
`ACK_JOURNAL` control and result types introduced by journal-sync. The watermark is the last EventId of the fully
consumed contiguous page and is never persisted by AgentD terminal mode.

Make `TerminalMain` acquire the terminal, resolve the target, construct observer/command lane/follower, enter raw
mode only after preflight succeeds, run `TerminalSession`, and close resources in reverse order. Error messages
must name the session or path and bounded failure kind without echoing input or arbitrary payload bytes.

**Step 4: Run all AgentD terminal tests**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.terminal.*,pro.deta.orion.agentd.AgentdMainTest'
```

Expected: PASS.

**Step 5: Commit**

```text
git add agentd/src/main/java/pro/deta/orion/agentd/terminal \
  agentd/src/test/java/pro/deta/orion/agentd/terminal agentd/src/main/java/pro/deta/orion/agentd/AgentdMain.java
git commit -m "Assemble AgentD local interactive terminal"
```

### Task 8: Verify against the real native host and document usage

**Files:**

- Modify: `agentd/pom.xml`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/terminal/LocalTerminalEndToEndTest.java`
- Create: `agentd/README.md`

**Step 1: Write a failing native end-to-end test**

Add `session-host-native` as a test dependency:

```xml
<dependency>
    <groupId>pro.deta.orion</groupId>
    <artifactId>session-host-native</artifactId>
    <version>${revision}</version>
    <scope>test</scope>
</dependency>
```

Copy the current-platform executable resource to a temporary executable file. Drive `TerminalSession` with the
fake terminal device while all runtime, filesystem journal, and Unix control components remain real.

The main scenario must:

1. start a shell command that prints a marker, reads one line, prints it and waits;
2. observe the first marker only through journal replay/tail;
3. send input and a changed terminal size through native control;
4. detach and prove both host and child remain alive;
5. attach again with a fresh terminal, replay each retained record exactly once in that invocation, send the
   final input, and observe `PROCESS_EXITED`; and
6. assert the returned exit code and restored terminal state.

Add a second scenario with `--ack-journal` and small native segment limits. Verify the durable
`control-retention-state` advances only in the opt-in run. The default scenario must leave that sidecar absent.
No Jetty server or central-server fixture may appear in either test.

**Step 2: Run the native test and verify it fails**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.terminal.LocalTerminalEndToEndTest'
```

Expected: FAIL until the native fixture and complete terminal assembly are connected.

**Step 3: Complete the fixture and write concise user documentation**

Document:

```text
java -jar agentd.jar daemon --server ...
java -jar agentd.jar terminal start --session-host ./session-host \
  --sessions-dir ./target/local-sessions -- /bin/zsh
java -jar agentd.jar terminal attach \
  --session-dir ./target/local-sessions/local-session-...
```

Explain raw-terminal requirements, `Ctrl-] d`, literal Ctrl-], replay behavior, independent host lifetime, exit
codes, POSIX-only scope, and the destructive/recovery tradeoff of `--ack-journal`. Do not rewrite historical
approved design documents merely because their examples predate the `daemon` subcommand.

**Step 4: Run focused and full development verification**

Run outside the sandbox:

```text
make run-test MODULE=agentd TEST='pro.deta.orion.agentd.terminal.*'
make run-test MODULE=agent-provisioning TEST='pro.deta.orion.provisioning.RemoteAgentdProvisionerTest'
mvn verify -Pdev -T 4
```

Expected: PASS.

**Step 5: Commit implementation and documentation**

```text
git add agentd/pom.xml agentd/src/test/java/pro/deta/orion/agentd/terminal/LocalTerminalEndToEndTest.java \
  agentd/README.md
git commit -m "Verify AgentD terminal against the native host"
```

### Task 9: Review, squash, transfer, and close the task

**Files:**

- Delete: `docs/plans/current-work/agentd/local-terminal/TASK.md`
- Modify: `docs/plans/current-work/agentd/TASK.md`
- Review: every file changed by the task branch

**Step 1: Request code review**

Use `superpowers:requesting-code-review`. Apply `docs/reviews/RULES.md`, inspect all task-branch changes, and fix
every blocking finding. Re-run the focused test owning each fix.

**Step 2: Run final branch verification**

Run outside the sandbox:

```text
mvn verify -Pdev -T 4
git diff --check
```

Expected: PASS and no whitespace errors.

**Step 3: Finish the dedicated worktree according to repository rules**

Use `superpowers:finishing-a-development-branch`. Delete the completed leaf task directory and remove its parent
link in the squashed commit. Squash all task-unique commits to:

```text
Run AgentD as a local interactive terminal [task: current-work/agentd/local-terminal]
```

Cherry-pick that commit to `main`; never merge. On `main`, run the required post-commit verification:

```text
make test
```

If a test failure is fixed after the commit, make the follow-up commit with the exact same subject for later
squashing. Remove the completed worktree and branch only after the cherry-pick, tests, and clean-tree checks all
succeed. Confirm `git worktree list` no longer contains the task worktree before reporting completion.

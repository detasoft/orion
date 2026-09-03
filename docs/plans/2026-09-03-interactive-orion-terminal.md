# Interactive Orion Terminal Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace Orion's informational SSH shell with a safe interactive PTY terminal over the shared command
dispatcher, including navigation, editing, authorized completion, resize handling, cancellation, and bounded
virtual-thread lifecycle.

**Architecture:** `core/command` owns transport-independent tree navigation, completion metadata, editing, terminal
rendering, and session state. `net/git-transport` adapts Mina streams and PTY signals, uses one virtual reader thread
per interactive channel, and submits only command-handler work to `OrionExecutor`. Git wire commands and noninteractive
Orion exec retain their existing adapters.

**Tech Stack:** Java 21 virtual threads, Apache Mina SSHD 2.13.2, Orion command core, Dagger 2, JUnit 5, AssertJ,
Maven.

---

### Task 1: Add shared visible command-tree navigation and completion

**Files:**
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandCompletion.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandLocation.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandNavigation.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/CommandNavigator.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandDefinition.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/DefaultCommandDispatcher.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/CommandNavigatorTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/DefaultCommandDispatcherTest.java`

**Step 1: Write failing navigator tests**

Build a synthetic tree with static children, allowed and denied dynamic candidates, visible and hidden actions,
named parameters, `where` fields, and enum values. Assert that navigation:

- resolves root, absolute paths, relative child paths, and parents without escaping root;
- returns the resolved resource chain needed by nested dynamic scopes;
- reports missing and authorized-only ambiguity without leaking denied candidates;
- lists only visible actions and allowed dynamic IDs/names in catalog order;
- completes namespaces, actions, dynamic selectors, named parameters, `where` fields, and enum values;
- returns a shared prefix for ambiguity and a unique insertion for one match.

Use completion metadata shaped as immutable named and `where` value maps:

```java
public record CommandCompletion(
        Map<String, List<String>> namedValues,
        Map<String, List<String>> whereValues) {
    public static CommandCompletion none() { /* empty immutable maps */ }
}
```

Add an overload preserving the existing nine-argument `CommandDefinition` constructor and defaulting completion to
`none()`, so current catalogs remain source-compatible.

**Step 2: Run the focused tests and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=CommandNavigatorTest,DefaultCommandDispatcherTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the navigation/completion types do not exist.

**Step 3: Implement immutable navigation and reuse it in dispatch**

`CommandNavigator` receives the root `CommandNode`. `locate` walks static children first and then dynamic resolvers,
preserving the dispatcher's exact missing/ambiguity semantics. `visibleEntries` applies action visibility and dynamic
`visible`, never raw catalog candidates. `complete` tokenizes only the prefix through the cursor and treats shell
metacharacters literally. Use ordinary loops and deterministic insertion order.

Refactor `DefaultCommandDispatcher` to use `CommandNavigator.locate` for path resolution. Retain the public constructor
that accepts `(CommandLineParser, CommandNode)` and add an injectable constructor accepting the navigator if useful.

**Step 4: Run all command-core tests and verify GREEN**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/command/src
git commit -m "Navigate visible Orion command scopes"
```

Run `make test` after the commit as required by `AGENTS.md`.

### Task 2: Implement the transport-independent editor and terminal renderer

**Files:**
- Create: `core/command/src/main/java/pro/deta/orion/command/terminal/TerminalInputEvent.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/terminal/TerminalLineEditor.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/terminal/TerminalDisplay.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/terminal/TerminalCommandRenderer.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/terminal/TerminalLineEditorTest.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/terminal/TerminalDisplayTest.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/terminal/TerminalCommandRendererTest.java`

**Step 1: Write failing editor tests**

Feed byte chunks rather than an `InputStream`. Assert incremental UTF-8 decoding; CR/LF; Backspace/Delete; arrow,
Home, and End escape sequences split across chunks; cursor movement by Unicode code point; bounded Up/Down history;
Tab; Ctrl-C; and Ctrl-D. Events distinguish submitted lines, completion requests, cancellation, EOF intent, and
redraw-only changes.

**Step 2: Run the editor test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=TerminalLineEditorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the editor types do not exist.

**Step 3: Implement the editor with bounded state**

Use code-point lists for the editable value and a `CharsetDecoder` for chunk boundaries. Cap history and line length
with documented constants. Parse only the supported CSI sequences; discard unknown sequences safely. Do not interpret
shell syntax.

**Step 4: Write failing display and renderer tests**

Assert root and nested prompts, ANSI redraw only when enabled, plain redraw without CSI codes, cursor restoration,
terminal-width-aware help/completion columns, width-aware `Rows`, and stable rendering of messages, objects, failures,
exits, unsupported streams, and attachments.

**Step 5: Implement display and terminal rendering**

`TerminalDisplay` serializes writes with `ReentrantLock`, never `synchronized`. `TerminalCommandRenderer` reuses the
plain result/exit mapping and adds interactive row layout based on current positive width. Keep all output UTF-8 and
flush complete frames.

**Step 6: Run command-core tests and verify GREEN**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am
```

Expected: PASS.

**Step 7: Commit**

```bash
git add core/command/src
git commit -m "Add Orion terminal editing and rendering"
```

Run `make test` after the commit.

### Task 3: Build the interactive terminal session state machine

**Files:**
- Create: `core/command/src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java`
- Create: `core/command/src/main/java/pro/deta/orion/command/terminal/TerminalCancellation.java`
- Test: `core/command/src/test/java/pro/deta/orion/command/terminal/InteractiveTerminalTest.java`

**Step 1: Write failing session tests**

Run the session with recording dispatcher/executor and chunked input. Cover:

- prompt username/current path and `/`, `..`, absolute, relative, `?`, and `help` controls;
- path-only navigation through authorized dynamic scopes;
- command requests carrying the current path, interactive presentation, width, and explicit metadata;
- history and completion integration including unchanged ambiguity output;
- Ctrl-C cancellation and future interruption while the reader continues accepting bytes;
- Ctrl-C line clearing while idle, Ctrl-D quit only at an empty idle prompt, EOF, and disconnect close;
- ignored input while active and suppression of a late result after cancellation;
- literal `touch`, `;`, `|`, redirects, substitutions, and backticks reaching only the Orion dispatcher;
- result delivery failure causing exit 1 exactly once.

**Step 2: Run the focused test and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am \
  -Dtest=InteractiveTerminalTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the session types do not exist.

**Step 3: Implement the terminal state machine**

`run(InputStream)` prints a prompt and repeatedly calls only `read(byte[], 0, buffer.length)` on a reusable buffer.
It never calls zero-argument `read()`. The caller owns its thread. Submit one dispatcher task at a time through an
injected `ExecutorService`; keep the returned `Future` and an atomic `TerminalCancellation`. Ctrl-C atomically wins the
active slot, cancels/interrupts the future, renders cancellation once, and prompts again. Resize only updates atomics.

Expose idempotent `close()` that marks closed, cancels active work, interrupts the recorded reader thread, and closes
input/output without performing blocking I/O under an intrinsic monitor. Do not use any thread-local state.

**Step 4: Run all command-core tests and verify GREEN**

```bash
mvn test -Pdev -T 4 -q -pl core/command -am
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/command/src
git commit -m "Run interactive Orion terminal sessions"
```

Run `make test` after the commit.

### Task 4: Replace the Mina informational shell with the virtual-thread adapter

**Files:**
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/OrionShell.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/SshCommandModule.java`
- Create: `net/git-transport/src/test/java/pro/deta/orion/transport/git/OrionShellTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`
- Modify: `net/transport/src/test/java/pro/deta/orion/transport/OrionTransportModuleTest.java`

**Step 1: Write failing Mina adapter tests**

Configure the created command with controllable Mina `IoInputStream` and `IoOutputStream` fakes whose futures complete
only when released or immediately closed. Also install synchronous streams that fail if touched. Assert:

- `start` returns promptly and creates one named virtual reader thread, not an OrionExecutor reader task;
- the initial PTY columns and ANSI capability come from Mina `Environment`;
- WINCH observes Mina's updated `COLUMNS` and changes subsequent rendering;
- EOF and direct input close terminate the reader;
- `destroy` marks closed, cancels/interrupts active command work, removes the signal listener, interrupts the virtual
  reader, closes input then outputs, and calls `ExitCallback` once;
- concurrent EOF, handler completion, and destroy still produce one callback;
- prompt, redraw, and results use only the asynchronous output path and keep their order;
- destroy marks the terminal closed and cancels work before terminal display close immediately aborts Mina output
  outside the output lock, releasing a pending writer;
- adapters wait through future listeners plus `CountDownLatch`, never Mina `await`/`verify`, per-I/O threads, or an
  intrinsic monitor around blocking I/O.

Extend the live state-machine test so the PTY shell accepts `help` and `quit`, and system-command input produces only
an Orion unknown-command result without creating its filesystem marker.

**Step 2: Run focused tests and verify RED**

```bash
mvn test -Pdev -T 4 -q -pl net/git-transport,net/transport -am \
  -Dtest=OrionShellTest,GitSshTransportStateMachineTest,OrionTransportModuleTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `OrionShell` still sends an informational message and exits.

**Step 3: Implement and wire the Mina adapter**

Make `OrionShell` injectable with the dispatcher, navigator, renderer, and `OrionExecutor`. Its command implements
Mina `AsyncCommandStreamsAware`, adapts all three async streams through listener/latch bridges, creates the explicit
authenticated `CommandContext`, installs the WINCH listener, and starts
one named virtual reader that runs the terminal over the async input adapter. It keeps atomics for reader, terminal,
completion, and destroyed state. Terminal display close rejects new writes and aborts its async adapter outside the
serialization lock; `destroy` can therefore follow terminal cancellation ordering without duplicate callback or a
pending-output lock inversion.

Inject the shell factory into `GitSshTransportService` and retain `sshd.close(true)` on service stop. Add Dagger
bindings only where constructor injection does not suffice. Do not change `SshCommandFactory` Git or exec routing.

**Step 4: Run focused module tests and verify GREEN**

```bash
mvn test -Pdev -T 4 -q -pl core/command,net/git-transport,net/transport -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Commit**

```bash
git add net/git-transport/src net/transport/src
git commit -m "Serve interactive Orion SSH terminals"
```

Run `make test` after the commit.

### Task 5: Add real SSH acceptance coverage and verify compatibility

**Files:**
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`
- Modify as needed: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`

**Step 1: Write failing end-to-end PTY tests**

Using the existing named-user setup, open a real PTY shell and cover prompt/help, editing/history, ambiguous
completion, resize, Ctrl-C of a blocking dispatcher fixture where available, Ctrl-D/quit, disconnect, relative
navigation, and system-command escape attempts. Keep assertions for Git clone/push/pull and noninteractive exec.

If domain catalogs needed for dynamic ambiguity are not assembled in the integration fixture yet, keep those cases in
the real command-tree/Mina module tests and use the end-to-end test for actual protocol lifecycle and security boundary.

**Step 2: Run the SSH integration class and verify RED/GREEN**

First run after adding assertions and before any required fixture change to confirm the intended failure. Then make
the minimum fixture adjustment and rerun:

```bash
mvn verify -Pdev -T 4 -q -pl tests/integration-test -am \
  -Dit.test=GitSshTransportEndToEndIT \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dexec.skip=true
```

Expected final result: PASS.

**Step 3: Commit acceptance coverage**

```bash
git add tests/integration-test/src net/git-transport/src/test
git commit -m "Verify interactive SSH terminal boundaries"
```

Run `make test` after the commit.

### Task 6: Review and verify the complete leaf

**Files:**
- Review all files changed since `d0e5f3a8ea5d13b7d398224cbb20aa2971a7b150`.

**Step 1: Re-read rules and invariants**

Read `docs/reviews/RULES.md`, applicable `@AiRule` comments, this plan, and the design. Confirm no per-I/O timeout
thread, no zero-argument Mina input read, no blocking I/O under intrinsic monitors, no system process path, exact-once
exit, authorized completion, and bounded virtual reader lifetime. Check source lines and run `git diff --check`.

**Step 2: Run routine development verification**

```bash
mvn verify -Pdev -T 4
```

Expected: PASS.

**Step 3: Run required full-project tests**

```bash
make test
```

Expected: `BUILD SUCCESS`.

**Step 4: Report for primary review**

Return base/head SHAs, every logical commit, changed files, exact verification results, and residual risks. Leave the
task owner, leaf directory, worktree, and branch in place. Do not squash, integrate into main, or clean up until the
primary review and explicit user gate.

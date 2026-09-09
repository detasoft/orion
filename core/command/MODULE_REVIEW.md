# Module Review: Interactive Terminal

Date: 2026-09-08

Status: static review; correctness findings remain open

## Scope and verification

This review covers the interactive terminal in `core/command`, its navigation and completion calls,
and its SSH integration through `OrionShell` in `net/git-transport`. It includes the production domain
source, related test sources, and the interactive SSH task tree. It is not a review of the entire
command subsystem or Git transport.

The findings below follow from source inspection. Slow catalog calls, terminal wrapping, and completion
inside a token were not reproduced in a live SSH session during this review. Maven verification was not
run, following [the repository review rules](../../docs/reviews/RULES.md). No local Surefire reports for
these modules or Failsafe reports for the integration tests were available, so this review does not
establish that the current build passes.

## Current implementation

The terminal is implemented and connected to SSH; it is not merely a design or task placeholder.

- [InteractiveTerminal] owns input editing, history, navigation, completion, command submission,
  cancellation, and prompt restoration. It uses the existing command graph and dispatcher.
- [OrionShell] adapts Mina asynchronous streams, starts a dedicated virtual reader for the SSH session,
  forwards terminal width changes, and closes the terminal with the channel. Command execution uses
  the shared executor; asynchronous command completion uses a virtual thread per active operation.
- The editor bounds history and line length and handles code-point editing and fragmented input.
  Command cancellation and late-result suppression have explicit lifecycle handling.
- Resource resolution and completion use the authorization-aware command model. The administrative
  terminal does not launch an operating-system shell, and recovery-enrollment sessions are rejected.

These are existing implementation properties to preserve when addressing the findings.

## Findings

### 1. Navigation, help, and Tab can block the input reader and delay Ctrl-C

**Evidence.** [InteractiveTerminal] calls `navigator.navigate` in `submit`, `navigator.complete` in
`complete`, and `navigator.locate` / `visibleEntries` in `help` synchronously from the input reader.
All these calls receive `CommandCancellation.never()`, bypassing the cancellable `ActiveCommand`
lifecycle used for dispatched commands.

The calls can reach real repository work. [DefaultOperatorDomainSource] implements `repositories()`
by finding every repository and reading its default head and refs before returning a snapshot.
Catalog lookup therefore cannot be assumed to be a cheap in-memory operation.

**Impact.** A slow repository lookup during navigation, help, or completion prevents that session's
reader from consuming further input, including Ctrl-C. Normal command cancellation does not cover
this path. The risk follows from the call chain; no slow-backend reproduction was performed.

**Correction direction.** Keep the reader available while catalog work runs, reusing the existing
operation and cancellation lifecycle where possible. Cancellation must reach the underlying work;
merely moving an indefinitely blocked call to another thread is insufficient.

**Missing coverage.** A delayed catalog lookup followed by Ctrl-C or channel close, including prompt
restoration and suppression of a result that arrives after cancellation.

### 2. Redraw assumes that the entire prompt and input occupy one screen row

**Evidence.** [TerminalDisplay] implements `redraw` with carriage return, clearing the current line
using `CSI 2 K`, rewriting the full prompt and input, and moving the cursor left. It has no terminal
width or record of previously occupied screen rows. The non-ANSI path similarly erases by writing
`previousFrameWidth` spaces. `InteractiveTerminal.resize` only updates the width value used for
rendering results and candidate lists; that value is not passed into `TerminalDisplay.redraw`.

**Impact.** Once input or a long prompt wraps, the next redraw begins on the current physical row
instead of the original prompt row. Previous fragments can remain visible, and horizontal cursor
movement cannot correctly return across wrapped rows. Shrinking the terminal can expose the same
problem. This is a static conclusion, not a recorded terminal-emulator reproduction.

**Correction direction.** Give redraw a defined viewport policy: either track and clear occupied rows
with width-aware cursor positioning, or render a bounded horizontal input view. Account for terminal
display-cell width when positioning the cursor.

**Missing coverage.** Input longer than the viewport, a long prompt, deletion after wrapping, cursor
movement across the wrap boundary, and shrinking the viewport with non-empty input.

### 3. Completion inside a token preserves and duplicates its old suffix

**Evidence.** [CommandNavigator] builds the replacement from `line.substring(0, cursor)` and then
appends `line.substring(cursor)`. It finds the start of the current token but does not find its end.
For a unique `whoami` candidate, completing `who|ami` produces `whoami ami`, where `|` denotes the
cursor position before completion. The inserted space is the normal suffix for a unique action.

**Impact.** Tab can turn an already valid command into a command with an unintended argument when
the cursor is inside the command name. Resource and argument completions use the same assembly path.
The example is derived directly from the implementation; no test was executed for it in this review.

**Correction direction.** Replace the complete token containing the cursor while preserving subsequent
arguments and placing the cursor after the replacement. Keep the existing conversion between UTF-16
indices in navigation and code-point indices in the editor.

**Missing coverage.** Completion at the beginning and middle of a token, preservation of following
arguments, and tokens containing supplementary Unicode characters.

## Remaining integration work

- [Streaming and monitoring](../../docs/plans/current-work/08_interactive-ssh-shell/02_streaming-monitoring.md)
  remains pending: bounded streams, backpressure, cancellation, and per-event authorization.
- [The session-host PTY gateway](../../docs/plans/current-work/08_interactive-ssh-shell/03_session-host-pty-gateway.md)
  remains pending: authorized attachment, binary input/output forwarding, resize, and detach without
  terminating the host session.
- [DefaultOperatorDomainSource] currently returns `Unavailable` for organizations, organization users
  and repositories, sessions, and proxies. Command definitions alone do not provide those integrations.
- Both task files above still depend on `../command-core-and-exec/TASK.md` and
  `../interactive-terminal/TASK.md`, whose task nodes have already been removed. Those dependencies
  need to be reconciled with the implemented foundation.

These pending features and stale task references are separate from the three correctness findings.

## Existing test coverage

There are 33 `@Test` methods across the [terminal test package](src/test/java/pro/deta/orion/command/terminal)
and [OrionShellTest]. Their source covers editing, history, fragmented input, rendering, cancellation,
result ordering, completion-thread behavior, blocked-output shutdown, and SSH lifecycle handling.
Navigator tests additionally cover navigation and completion through the authorized command graph.

[GitSshTransportEndToEndIT] contains live SSH scenarios for interactive PTY editing, completion, resize,
and preventing operating-system shell execution. These are existing test scenarios, not verification
results from this review. They do not establish coverage of the three cases identified above.

[InteractiveTerminal]: src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java
[TerminalDisplay]: src/main/java/pro/deta/orion/command/terminal/TerminalDisplay.java
[CommandNavigator]: src/main/java/pro/deta/orion/command/CommandNavigator.java
[OrionShell]: ../../net/git-transport/src/main/java/pro/deta/orion/transport/git/OrionShell.java
[DefaultOperatorDomainSource]: ../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/DefaultOperatorDomainSource.java
[OrionShellTest]: ../../net/git-transport/src/test/java/pro/deta/orion/transport/git/OrionShellTest.java
[GitSshTransportEndToEndIT]: ../../tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java

# Interactive Orion Terminal Design

## Goal

Replace the informational SSH shell with an interactive Orion-only terminal over the shared command dispatcher.
The terminal provides safe navigation, help, editing, history, authorized completion, cancellation, resize-aware
rendering, and predictable disconnect behavior without invoking an operating-system shell or interpreter.

## Architecture

The command module gains a transport-independent command-tree navigator and terminal engine. The navigator walks
the same immutable `CommandNode` graph and dynamic `ScopedResourceResolver` instances as the dispatcher. It exposes
only static children, dynamic IDs and names, and actions visible to the authenticated context. Command definitions
may attach completion values for named parameters, `where` fields, and enums without coupling handlers to a terminal.

The terminal engine owns the current absolute path, editable line and cursor, bounded in-memory history, terminal
width, ANSI capability, and the active command cancellation token. Frontend controls are interpreted before command
dispatch:

- `/` changes to the root scope;
- `..` changes to the parent scope;
- a visible absolute or relative path changes scope;
- `?` and `help` render visible children and actions at the current scope;
- `quit` and Ctrl-D at an empty prompt close the shell successfully.

Every other complete line becomes a `CommandRequest` using the current path and the existing audited
`CommandDispatcher`. Shell metacharacters remain ordinary Orion input, so they can only produce parser or dispatch
failures and can never start a process. A terminal renderer formats structured results using the current width and
uses ANSI only when the interactive SSH environment advertises a usable terminal.

`net/git-transport` owns a thin Apache Mina `ShellFactory` adapter. It creates the authenticated session context,
provides Mina streams and PTY metadata, registers a WINCH listener, and owns thread and channel lifecycle. Git exec
and noninteractive Orion exec remain on their existing paths.

## Virtual-thread lifecycle

Each interactive PTY session uses exactly one dedicated virtual thread for its entire input lifetime. It is created
with `Thread.ofVirtual()` by the Mina command and is not submitted to `OrionExecutor`. A blocking reader is therefore
cheap and cannot consume one of Orion's bounded platform threads while a user sits idle. The shared `OrionExecutor`
is used only for command-handler work, allowing the reader to continue recognizing Ctrl-C and resize while a handler
is active.

Each active command may additionally own one short-lived completion virtual thread. The bounded executor runs only
the dispatcher and handler; after the dispatcher returns, it starts the completion thread and immediately releases
its platform worker. The completion thread performs ordered result rendering and the following prompt, so SSH remote
window backpressure can park only a virtual thread. It is attached to the active-command lifecycle and interrupted on
disconnect, while immediate async-output close releases any pending write. It is neither a per-I/O thread nor a
timeout mechanism, and the one-active-command rule bounds it to at most one per session.

This is compatible with the pinned runtime under the following enforced invariants:

- The root build compiles with Java release 21, and the checked runtime is OpenJDK 21, where virtual threads are a
  final API.
- Mina 2.13.2 selects `AsyncDataReceiver`, `ChannelAsyncInputStream`, and `ChannelAsyncOutputStream` only when the
  command implements `AsyncCommandStreamsAware`. The shell implements that complete interface so Mina never supplies
  either the synchronous input pipe or the synchronous channel outputs to the terminal.
- This avoids two Java 21 carrier-pinning paths in Mina's synchronous adapters. `ChannelPipedInputStream.read()`
  synchronizes on a one-byte array before delegating to a potentially blocking read, while
  `ChannelOutputStream.write(byte[], off, len)` is synchronized and may wait for remote-window space while holding
  that intrinsic monitor.
- Core-facing input and output adapters start one Mina asynchronous operation at a time, attach a future listener,
  and wait on a `CountDownLatch`. The listener signals the latch after success, EOF, failure, or immediate close.
  `CountDownLatch` parks a virtual thread through AQS; the adapters never call Mina future `await` or `verify`, whose
  implementation uses intrinsic-monitor waiting. Input still exposes only bulk reads to the terminal and copies from
  a writable `ByteArrayBuffer`; output wraps immutable bytes in a read-mode buffer until its future completes.
- No intrinsic monitor is held across input reads, output writes, executor submission, future cancellation, thread
  interruption, stream close, or `ExitCallback`. Mutable lifecycle state uses atomics; output serialization uses a
  `ReentrantLock`, with state captured before I/O where practical.
- The virtual thread does not depend on `ThreadLocal`, inheritable thread-local state, carrier identity, or affinity.
  All identity, path, presentation, cancellation, and audit metadata are explicit immutable values.

Normal completion calls the exit callback once after every ordered asynchronous output future has completed.
Disconnect and destruction use this order: atomically mark the shell and terminal closed, cancel active work,
interrupt the reader, close asynchronous input, immediately close asynchronous output, remove the WINCH listener,
close asynchronous stderr, and invoke the exit callback at most once. Terminal display close atomically rejects new
writes and closes its adapter outside the output serialization lock. Immediate Mina close therefore completes a
pending write future and releases its latch before that writer unwinds and releases the lock, so a non-reading peer
cannot make destroy wait behind a remote-window write. I/O and callback calls occur outside lifecycle locks.
Concurrent command completion, Ctrl-C, EOF, output failure, and destroy converge through atomics and a single
completion method.

Mina's `CommandLifecycle` contract explicitly expects `start` to spawn a thread and calls `destroy` after a client
disconnect. Its channel close path destroys the command and closes receivers and streams. `GitSshTransportService`
uses `sshd.close(true)` during server stop, so active channels reach `destroy`. Direct asynchronous input close
completes the pending read even if the channel teardown order changes. The reader has no independent retry loop after
close, and tests must prove it terminates, bounding its lifetime to the channel.

## Editing and presentation

The editor consumes bytes, decodes UTF-8 incrementally, and recognizes CR/LF, Backspace/Delete, Home/End, arrow keys,
Ctrl-C, Ctrl-D, and Tab. Up/Down traverse a bounded history without duplicating adjacent commands. Left/Right and
Delete operate on Unicode code points rather than UTF-16 halves. When ANSI is enabled, redraw clears the current line,
prints the prompt and buffer, then restores the cursor. The plain fallback uses carriage return/backspace without CSI
sequences.

Completion is computed at the cursor. A unique match is inserted, a shared prefix extends ambiguous input, and an
unchanged ambiguous set is rendered in terminal-width-aware columns before the prompt is redrawn. Candidates include
visible static namespaces and actions, allowed dynamic IDs and names, named parameters, `where` fields, and registered
enum values. Denied dynamic resources and invisible actions never appear.

The prompt is `[username@orion] >` at root and `[username@orion /path] >` below root. Rows and help entries are laid
out to fit the current positive width; narrow terminals fall back to one item per line. WINCH reads Mina's concurrently
updated `COLUMNS` environment value and atomically updates the terminal presentation used by later redraws/results.

## Error handling and concurrency

Only one command is active per terminal. Additional ordinary input while it runs is ignored with a bell, while Ctrl-C
sets the explicit cancellation token, interrupts the handler future, renders a cancelled outcome, and returns to a
fresh prompt. A late handler result cannot overwrite the cancellation result. Ctrl-C at an idle prompt clears the
line. Ctrl-D is `quit` only at an empty idle prompt; otherwise it is ignored.

All output, prompt redraws, and completion lists pass through one output writer guarded by a `ReentrantLock`. The
reader and command-completion virtual thread may contend on that lock, and the lock remains held while the async future
completes to preserve frame order. This is virtual-thread-safe because both the lock and listener latch use AQS rather
than an intrinsic monitor. Display close rejects later writes and aborts the Mina stream outside that lock, so it does
not wait for a pending writer. Delivery failure closes the session and reports exit code 1 exactly once. Expected
parse, authorization, cancellation, and renderer failures remain structured command results.

## Verification

Core tests cover visible navigation through static and authorized dynamic scopes, completion ambiguity and metadata,
relative and absolute navigation, help, editor cursor/history behavior, terminal-width rendering, ANSI gating,
cancellation races, EOF, and shell-metacharacter dispatch boundaries.

Mina adapter tests use controllable asynchronous streams and prove that the reader reaches neither synchronous input
nor synchronous output, waits through listeners, preserves write order, and terminates when immediate close completes
pending futures. They also cover WINCH, disconnect during a pending output or command, output failure, and exactly-once
exit. A live SSH test exercises a PTY shell (including ordinary output through the async path) and confirms arbitrary
process syntax cannot create a marker. Existing Git wire and noninteractive exec tests remain unchanged and are rerun.

## Out of scope

- Credential, repository, organization, session, proxy, and system command implementations owned by later leaves.
- Query pagination, JSON, streaming monitors, and session-host attachment.
- Any operating-system process, shell, command substitution, redirect, pipe, or environment expansion.

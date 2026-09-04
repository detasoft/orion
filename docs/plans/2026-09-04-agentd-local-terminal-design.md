# AgentD Local Interactive Terminal Design

Status: approved on 2026-09-04.

## Context

Developers need to exercise `session-host` locally without starting the Orion
server. Directly reading the child PTY would bypass the production boundary:
`session-host` owns the PTY, publishes output through its durable journal, and
accepts input and terminal changes through its control endpoint. The local tool
must use those same paths so it remains useful for protocol, retention, restart,
and recovery testing.

AgentD already owns the Java implementations needed to launch and discover
native sessions, read segmented journals, decode known events, and deliver
native controls. The local tool therefore belongs in the existing AgentD
executable rather than a separate module or a second protocol implementation.

## Selected Architecture

`AgentdMain` becomes an explicit command router with two top-level modes:

```text
agentd daemon --server HTTPS_URI [daemon options]
agentd terminal start [terminal and session options] -- COMMAND...
agentd terminal attach --session-dir PATH [terminal options]
```

`daemon` is the only mode that parses server configuration, consumes the
single-use launch permit from stdin, constructs `Agent`, or initializes the
HTTP/2 transport. The former subcommand-free daemon syntax is intentionally
removed. Remote provisioning, launch fixtures, tests, and user-facing examples
must pass `daemon` explicitly.

`terminal` constructs only local AgentD components. `start` maps CLI values to
a `SessionSpec`, generates a session ID and start CommandId when the caller does
not supply them, launches the native executable through `NativeRuntime`, and
then attaches to the resulting session directory. `attach` validates the
existing manifest and live control endpoint without launching a process.

The first release supports interactive POSIX terminals on macOS and Linux.
Windows Console support is out of scope. A narrow terminal adapter owns raw
mode, byte input/output, the current window size, resize notifications, and
restoration. The orchestration layer depends on that adapter rather than on
global streams so deterministic tests do not require a real TTY.

## Journal and Control Flow

The terminal controller starts with no private durable cursor. It reads the
oldest retained journal record through the stable tail, renders each
`PTY_OUTPUT` payload byte-for-byte, and observes command and lifecycle records
needed to recover the next operation sequence. Unknown event types do not
affect the screen but still advance the in-memory journal cursor. After catch-up
the existing journal availability monitor wakes bounded incremental reads of
the active tail. Polling remains a safety net for missed or overflowed
filesystem notifications.

The input side sends bounded stdin chunks as `INPUT` operations. The current
terminal dimensions initialize the session and subsequent POSIX window changes
become ordered `RESIZE` operations. Every established-session operation uses a
fresh CommandId, the canonical Agent protocol command envelope, and the next
operation sequence recovered from journal history. The tool uses the same
control codec, retry identity, and serial ordering as daemon mode; it does not
add a simplified testing-only native protocol.

Terminal control bytes such as Ctrl-C are passed to the child PTY as input, so
the remote line discipline retains normal behavior. `Ctrl-] d` is a local
detach escape and never stops the host. Two consecutive Ctrl-] bytes send one
literal Ctrl-] to the child. EOF, local termination, and orchestration failure
also detach without sending `TERMINATE`.

`PROCESS_EXITED` is the authoritative end of the interactive session. The tool
drains all journal records through that event, restores the local terminal, and
uses the recorded child exit code as its own exit code where the platform can
represent it.

## Journal Acknowledgement

Terminal mode sends no journal acknowledgement by default and persists no
server cursor. This keeps normal local inspection non-destructive and permits a
later invocation to recover solely from retained session state.

The explicit `--ack-journal` option enables a testing aid. After the tool has
fully decoded and delivered a contiguous journal page, it sends that page's
last EventId through `ACK_JOURNAL`. It never acknowledges an incomplete record,
a gap, a corrupt page, or output that failed to reach the local terminal.
Watermarks remain monotonic for the lifetime of the invocation.

Unlike the real server, terminal mode does not persist a durable replica.
Usage text must therefore warn that `--ack-journal` may make old segments
eligible for deletion and can prevent a later stateless attach from recovering
the command prefix. If required history is no longer available, the tool may
replay the retained output but must not guess an operation sequence or send new
controls.

## Failure Handling and Lifecycle

Raw terminal state is an acquired resource. It is restored through structured
close handling and a shutdown hook covering normal process exit, detach,
interrupt, journal/control failure, and unexpected runtime exceptions. Closing
the Java tool never closes the native PTY or kills `session-host`.

Launch failures and journaled `SESSION_START_FAILED` diagnostics are reported
without leaving a raw terminal behind. An invalid manifest, unreachable control
endpoint, journal gap, corrupt complete record, or unsafe recovery boundary is
a session-local terminal error. The diagnostic identifies the session and
boundary without dumping arbitrary journal payloads or command input.

An incomplete active-tail record is not an error; the follower retains its
position and waits for more data. Segment rotation, compression, file
replacement, watch overflow, and timeouts reuse the journal reader's existing
resume rules. A control rejection or ambiguous delivery is reported and stops
accepting additional local input, but the output side may drain already durable
events before the tool exits. ACK failure disables further acknowledgements and
is reported without stopping the host.

## Testing

Pure Java tests use fake terminal, journal, monitor, runtime, and control seams
to cover:

- top-level `daemon` and `terminal` dispatch without reading the wrong stdin;
- terminal `start` and `attach` validation and component assembly;
- retained replay followed by live output without duplicates;
- bounded input, operation ordering, resize coalescing, and detach escaping;
- default no-ACK behavior and opt-in monotonic acknowledgement;
- incomplete tails, rotation, replacement, watch overflow, gaps, corrupt
  records, control failures, and output failures;
- restoration of the original terminal state on every exit path; and
- migration of remote AgentD provisioning to the explicit `daemon` command.

A POSIX integration test launches the real `session-host`, interacts through a
pseudo-terminal, verifies journal-derived output and live input/resize, detaches
without killing the host, reattaches, and observes the final process exit. A
second scenario enables `--ack-journal` and verifies the native durable
acknowledgement watermark. No central or in-process HTTP server participates.

## Non-Goals

- Implementing an Orion server, HTTP endpoint, or server journal store.
- Persisting a local replication cursor or server-authoritative command state.
- Providing a terminal emulator, ANSI parser, screen model, scrollback store,
  or web UI.
- Supporting Windows Console, multiple simultaneous viewers, collaborative
  input, file transfer, or harness-specific event rendering.
- Changing `session-host` ownership of PTY processes, journal durability, or
  control idempotency.

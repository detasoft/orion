# Run AgentD as a Local Terminal

Status: todo

Provide a local AgentD path for starting and attaching to an independent
`session-host` without an Orion server. The launch-only slice is complete in
`e3822a4a`; the remaining children add interactive attachment and explicitly
tracked follow-up capabilities.

## Architecture

- `session-host` continues to own the PTY, child process tree, durable journal,
  and native control endpoint.
- Local terminal mode renders output only from the production journal and sends
  input or resize only through source-aware `MANUAL` native controls.
- Local mode does not construct `Agent`, read a server launch permit, initialize
  HTTP/2 transport, recover `SERVER` operation sequences, or persist a cursor.
- Start-and-attach and attach-existing converge on one terminal-session path.
- Journal retention acknowledgement and explicit daemon command routing remain
  separate executable follow-ups rather than hidden optional scope.

## Interaction Contract

- The initial release supports interactive POSIX terminals on macOS and Linux.
- Retained `PTY_OUTPUT` is replayed byte-for-byte before live following.
- Unknown records advance the in-memory EventId cursor without terminal output.
- Input and changed terminal dimensions are sent once in one bounded manual
  lane. An uncertain delivery is never retried.
- `Ctrl-] d` detaches locally; `Ctrl-] Ctrl-]` sends one literal `Ctrl-]`.
- Detach, EOF, interruption, or AgentD failure never sends `TERMINATE`.
- `PROCESS_EXITED` is the authoritative child completion record.
- Raw terminal state is restored on every exit path.

## Failure Boundary

Invalid session state, unreachable control, a missing journal segment, corrupt
complete journal data, output failure, or control failure produces a bounded
session-local diagnostic. Numeric EventId jumps alone do not prove missing
history. An incomplete active tail is a wait boundary, not an error. Closing
local terminal mode never stops the host or child.

## Acceptance

- A real POSIX host can be started, detached, attached again, controlled, and
  observed through process exit without an Orion server.
- Default inspection is non-acknowledging and restartable from retained local
  state.
- Every capability intentionally deferred from the first usable attach remains
  represented by a child task.

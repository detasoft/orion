# Run AgentD as a Local Interactive Terminal

Status: todo
Design: ../../../2026-09-04-agentd-local-terminal-design.md
Plan: ../../../2026-09-04-agentd-local-terminal.md
Depends on: ../journal-sync/TASK.md, ../command-orchestration/TASK.md, and the
native session-host control/journal contracts.

Add an explicit local terminal mode to the AgentD executable so developers can
launch or attach to a `session-host` and exercise the production journal and
control paths without running the central server.

## Scope

- Route production startup through `agentd daemon` and migrate provisioning,
  tests, and documentation from the former implicit daemon invocation.
- Provide `agentd terminal start` for launching a new native session and
  `agentd terminal attach` for attaching to an existing session directory.
- Run a POSIX terminal in raw mode, send input and terminal-size changes through
  the native control protocol, and render output only from durable journal
  events.
- Replay the retained journal before following its live tail, recover command
  ordering without a private cursor, and fail rather than guess across a gap or
  corrupt suffix.
- Leave journal acknowledgement disabled by default. Send monotonic
  `ACK_JOURNAL` watermarks only when the user explicitly passes
  `--ack-journal`.
- Detach without stopping `session-host`, restore the local terminal on every
  exit path, and avoid initializing server identity, permits, Jetty, or any
  local HTTP server in terminal mode.

## Acceptance

- Both launch and attach work against a real POSIX `session-host` with no Orion
  server process.
- Retained output is replayed once, new output is followed without duplicates,
  stdin and resize controls are ordered, and process exit is reflected in the
  tool's exit status.
- `ACK_JOURNAL` is absent by default and follows only fully consumed records
  when enabled explicitly.
- Detach, errors, interrupts, gaps, incomplete tails, rotation, and failed
  control delivery leave the host independent and always restore the local TTY.
- The production provisioning path invokes `agentd daemon`, and the old
  subcommand-free daemon syntax is rejected with useful usage text.

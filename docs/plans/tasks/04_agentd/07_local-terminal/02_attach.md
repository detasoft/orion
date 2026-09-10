# Attach a Local Terminal to a Session Host

Status: todo
Depends on: completed local launch `e3822a4a`, completed paged journal reader
`02e74a3a`, and completed source-aware native controls (`09ed12c0`, `b3c8953c`,
`5005ae2a`)

- Owner: codex, session terminal-attach-7c42, branch
  `codex/agentd-terminal-attach`, worktree `.worktrees/agentd-terminal-attach`,
  started 2026-09-10 23:01 Europe/Amsterdam.

Add `agentd terminal attach` and extend the existing `terminal start` path to
attach after launch through the production journal and native control paths.

## Required Result

- Acquire and restore one local POSIX terminal.
- Resolve an explicit existing session directory or the directory returned by
  the existing local launcher.
- Replay retained output, follow live journal records without duplicates, and
  stop after draining `PROCESS_EXITED`.
- Send each manual input and changed terminal size exactly once through the
  shared `SessionControlClient` with source `MANUAL`.
- Detach without stopping `session-host` and support a later fresh attach.

## Preserved Invariants

- `session-host` remains the sole owner of the PTY, child processes, journal,
  and effect execution.
- Manual sequences are live response-correlation values only. They are not
  recovered from history and may repeat across controls or invocations.
- An ambiguous manual delivery is reported and never retried.
- Local reading sends no `ACK_JOURNAL`, persists no cursor, and is independent
  of server journal replication and `SERVER` sequence recovery.
- Existing server-mode AgentD invocation remains unchanged in this task.

## Design

Keep `AgentdMain` as the existing server entry point plus its current
`terminal` branch. Add `terminal attach --session-dir PATH`; make
`terminal start` launch through `LocalSessionLauncher` and then pass its
`SessionLaunchResult.Started` directory into the same attach coordinator.

A narrow POSIX terminal adapter owns raw mode, input/output, current dimensions,
resize notification, restoration, and a shutdown hook. It is acquired only
after CLI and session preflight succeeds.

The journal follower uses `FileSystemSessionJournalReader` with a disposable
`JournalReadPosition` and an in-memory EventId cursor. It drains `PAGE_LIMIT`
immediately and uses `JournalAvailabilityMonitor` after `INCOMPLETE_TAIL` or a
stable tail. File rotation, replacement, and watch overflow reuse the reader's
existing cursor-resume behavior. `PTY_OUTPUT` bytes are written and flushed in
journal order; unknown records only advance the cursor.

One bounded serial manual-control lane converts terminal input chunks and
coalesced size changes to `ControlCommand.Input` and `ControlCommand.Resize`.
It uses no server envelope. The detach escape is parsed across arbitrary input
chunk boundaries. Closing the coordinator stops input and following, restores
the terminal, and never sends terminate.

## Implementation Plan

1. Add failing CLI and target-resolution tests for attach-existing and for the
   existing start path handing its launched directory to attach. Cover malformed
   options, manifest/journal failure, unreachable control, and exited replay.
2. Implement the smallest terminal options and target model using
   `JsonSessionManifestReader`, the existing journal reader, and the shared
   control probe/client. Do not scan or claim unrelated sessions.
3. Add failing terminal-resource tests, then implement the POSIX adapter with
   idempotent raw-mode restoration and resize notification. Keep platform types
   behind the adapter.
4. Add failing follower tests for retained and live output, page limits,
   incomplete tails, rotation/replacement, unknown records, process exit, gap,
   corruption, and output failure. Implement bounded journal consumption with
   no acknowledgement path.
5. Add failing input/coordinator tests for chunking, escape parsing, resize
   coalescing, ordering, detach, EOF, control rejection, ambiguous delivery,
   concurrent output, and terminal restoration. Implement one serial MANUAL
   lane and structured close behavior.
6. Add a real-host POSIX test that starts a command, observes journal output,
   sends input and resize, detaches while host and child remain alive, attaches
   again, and observes the recorded exit without an Orion server.
7. Document the supported start/attach commands, detach escape, replay behavior,
   exit codes, POSIX boundary, and independent host lifetime.

Use test-first development for every behavioral step. Focused checks use
`make run-test MODULE=agentd TEST='<classes>'`; final pre-commit verification is
`mvn test -Pdev -T 4`.

## Acceptance

- Start-and-attach and attach-existing work against a real POSIX host without an
  Orion server.
- Output replay, live input, resize, detach, process exit, required-history
  gaps, and terminal restoration are covered through observable behavior.
- No server recovery, durable cursor, acknowledgement, host lifecycle ownership,
  or alternate native protocol is introduced.

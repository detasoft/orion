# Attach a Local Terminal to a Session Host

Status: todo
Depends on: completed local launch `e3822a4a`, ../02_journal-sync.md,
../04_command-orchestration.md, and
source-aware native controls (completed in `09ed12c0` and `b3c8953c`)

Add `agentd terminal attach` and extend `terminal start` to attach after launch
through the production journal and native control paths.

## Scope

- Acquire and restore a local POSIX terminal, replay retained output, and follow
  durable journal events.
- Send each manual input and resize operation once, using its sequence only for
  live response correlation. Never retry an uncertain delivery.
- Detach without stopping `session-host`; support attaching to an existing
  session directory.
- Keep manual journal reading non-acknowledging and independent from server
  replication state.

## Acceptance

- Start-and-attach and attach-existing work against a real POSIX host without an
  Orion server.
- Output replay, live input, resize, detach, process exit, gaps, and terminal
  restoration follow the approved interactive-terminal design.

## Boundary

Do not duplicate server command recovery, durable cursors, or host lifecycle
ownership in local terminal mode.

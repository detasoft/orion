# Attach a Local Terminal to a Session Host

Status: todo
Depends on: 01_launch.md, ../02_journal-sync.md,
../04_command-orchestration.md, and
[source-aware native controls](../../05_native-session-host/07_source-aware-controls.md)

Add `agentd terminal attach` and extend `terminal start` to attach after launch
through the production journal and native control paths.

## Scope

- Acquire and restore a local POSIX terminal, replay retained output, and follow
  durable journal events.
- Send manual input and resize operations with connection-scoped sequencing.
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

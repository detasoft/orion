# Build the Native Session Host for the Agent Harness

Status: todo
Detailed plan: ../../2026-09-01-native-session-host.md
Journal format: ../../2026-09-02-session-journal-cbor-sequence.md

Create a standalone Rust `session-host` that owns an interactive PTY/ConPTY
child, persists a durable ordered session journal, accepts local control
commands, and survives independently of `agentd`.

## Scope

- Preserve raw terminal bytes and resize history for deterministic replay.
- Keep one host-assigned order across terminal, process, and harness events.
- Support recovery, bounded retention, session discovery, and control reconnects.
- Restrict the Linux child process tree with an optional Landlock policy whose
  rule failures are fail-closed and whose capability mismatch is non-fatal.
- Publish stable journal and control compatibility fixtures for external
  consumers such as `agentd`.
- Keep terminal emulation, central-server transport, and semantic agent models
  outside the host.

## Child Tasks

- [ ] [Reduce the journal writer API](journal-writer-api/TASK.md)
- [ ] [Make journal durability operations explicit](journal-durability/TASK.md)
- [ ] [Move acknowledged retention off the writer path](asynchronous-journal-retention/TASK.md)
- [ ] [Harden Linux process-tree control](linux-process-tree-control/TASK.md)
- [ ] [Expose process control and PTY closure](process-control-and-pty-closure/TASK.md)
- [ ] [Coordinate session termination](termination-coordination/TASK.md)
- [ ] [Add ordered harness event ingress](harness-events/TASK.md)
- [ ] [Implement the Windows ConPTY host](windows-host/TASK.md)
- [ ] [Package targets and verify end-to-end acceptance](release-and-acceptance/TASK.md)
- [ ] [Run without restrictions when Landlock is unavailable](landlock-capability-fallback/TASK.md)

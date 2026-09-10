# Add Platform Status and Lifecycle Resilience

Status: todo
Depends on: 01_control-connection-lifecycle/TASK.md, 02_journal-sync.md,
04_command-orchestration.md

Extend the established control lifecycle with machine reporting, isolation
under journal and command load, observability, and coordinated shutdown.

## Scope

- Collect bounded CPU, memory, disk, OS, architecture, version, runtime,
  capability, and session snapshots without delaying heartbeat.
- Detect PTY, ConPTY, Landlock, Docker, Java, Git, Claude, Codex, and supported
  sandbox modes without treating optional tools as startup requirements.
- Integrate machine reporting and per-session journal work with the existing
  control scheduling so output backpressure cannot starve heartbeat or commands.
- Contain journal and command failures to the affected session and expose
  useful combined diagnostics without secrets or raw payloads.
- Extend control/discovery shutdown to stop new commands and flush bounded
  journal work without terminating any session host.
- Test slow metrics, noisy and corrupt sessions, offline operation, fairness,
  heartbeat under load, and shutdown or restart with active commands and journals.
- Reconnect backoff, basic heartbeat, and control/discovery resource ownership
  are implemented once in 01_control-connection-lifecycle/TASK.md.

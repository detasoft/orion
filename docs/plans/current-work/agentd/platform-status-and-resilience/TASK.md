# Add Platform Status and Lifecycle Resilience

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: ../identity-and-registration/TASK.md,
completed AgentD HTTP/2 transport, ../journal-sync/TASK.md,
../command-orchestration/TASK.md

Complete AgentD's machine reporting, scheduling isolation, reconnect policy,
observability, and safe lifecycle behavior.

## Scope

- Collect bounded CPU, memory, disk, OS, architecture, version, runtime,
  capability, and session snapshots without delaying heartbeat.
- Detect PTY, ConPTY, Landlock, Docker, Java, Git, Claude, Codex, and supported
  sandbox modes without treating optional tools as startup requirements.
- Apply exponential reconnect backoff with jitter and isolate control,
  heartbeat, per-session journal queues, and future low-priority traffic.
- Contain protocol and journal failures to one connection or session and expose
  useful logs and metrics without secrets or raw payloads.
- Gracefully stop new commands, flush bounded protocol work, close AgentD
  resources, and exit without terminating any session host.
- Test slow metrics, noisy and corrupt sessions, offline operation, fairness,
  backoff reset, heartbeat under load, and graceful or abrupt AgentD restart.

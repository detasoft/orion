# Integrate Session Discovery and Replay with agentd

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: ../contracts-and-build/TASK.md, ../journal-core/TASK.md,
../unix-process-host/TASK.md, ../journal-retention/TASK.md

Add the JVM-side integration without making `agentd` the owner of hosted
processes.

## Scope

- Discover session directories and determine host endpoint availability,
  journal readability, host liveness, and recorded child state.
- Read v1 journals from a timestamp cursor, skip unknown event types, and expose
  retention gaps explicitly.
- Use filesystem notifications only as wake-ups and recover from missed events,
  overflow, and restart by rescanning durable files.
- Implement the platform-neutral control client with reconnect and safe input-ID
  retry behavior.
- Launch hosts so they survive `agentd` termination, then reattach to discovered
  sessions without relying on old in-memory state.
- Test initial discovery, incremental reads, corrupt tails, notification loss,
  retained-history gaps, control reconnect, and kill/restart recovery.

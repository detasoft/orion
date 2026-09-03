# Provision Remote AgentD Machines

Status: active
Scheduling override: the SSH bootstrap foundation may use key material APIs
already on `main` before the aggregate key-material task completes.
Runtime components: ../agentd/TASK.md and ../native-session-host/TASK.md

Let Orion connect to remote machines over SSH and reconcile the runtime needed
to make them available as AgentD workers.

## Child Tasks

- [ ] [Add administration and end-to-end acceptance](administration-and-end-to-end-acceptance/TASK.md)
- [ ] [Fix the stalled SSH operation watchdog baseline](stalled-operation-watchdog-baseline/TASK.md)

## Completion Boundary

Orion must upload compatible AgentD and session-host artifacts, launch AgentD
detached from SSH, and prove through the real control path that the resulting
AgentD can launch a session host.

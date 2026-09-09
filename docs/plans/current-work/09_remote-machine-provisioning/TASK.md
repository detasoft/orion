# Provision Remote AgentD Machines

Status: active
Scheduling override: the SSH bootstrap foundation may use key material APIs
already on `main` before the aggregate key-material task completes.
Runtime components: ../04_agentd/TASK.md and ../05_native-session-host/TASK.md

Let Orion connect to remote machines over SSH and reconcile the runtime needed
to make them available as AgentD workers.

## Completion Boundary

Orion must upload compatible AgentD and session-host artifacts, launch AgentD
detached from SSH, and prove through the real control path that the resulting
AgentD can launch a session host.

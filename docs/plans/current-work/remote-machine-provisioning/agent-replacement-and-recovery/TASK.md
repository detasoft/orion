# Replace and Recover AgentD Safely

Status: todo
Depends on: ssh-runtime-bootstrap/TASK.md

Reconcile one remote AgentD process without disturbing native session hosts.

## Scope

- Verify the recorded process identity before signalling an old AgentD.
- Require confirmed termination before launching a replacement.
- Apply startup and offline-recovery timeouts with bounded retry backoff.
- Atomically update versions without terminating session-host processes.
- Preserve actionable diagnostics for uncertain process identity or privilege
  failures.

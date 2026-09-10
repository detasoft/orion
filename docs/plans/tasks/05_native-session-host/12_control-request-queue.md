# Evaluate a Session-Host Control Request Queue

Status: deferred (optional; need not established)
Related: TASK.md
Review: ../../../session-host/MODULE_REVIEW.md

Consider a bounded queue for incoming session-host control requests if observed
concurrency or resource use requires it. This is a future investigation, not a
required feature or release prerequisite. Leave it deferred until there is a
concrete need.

## Scope

- Establish the required number of concurrent control clients and pending
  requests, including idle connections and clients with blocked PTY input.
- Compare a simple connection/admission limit with a bounded request queue.
  Add a queue only if the simpler limit cannot satisfy the observed need.
- If needed, define capacity, overload responses, worker ownership, and shutdown
  behavior through the existing control admission and execution path.
- Preserve sequence replay protection, transient `RECEIVED`, and journaled
  `COMMAND_RESULT`. Keep `TERMINATE` available while another connection has a
  blocked `INPUT`; ordinary queue capacity must not prevent termination.
- If implemented, cover normal concurrent requests, overload, disconnects,
  blocked input with termination, and finalization of admitted operations.

## Outcome

Record whether a queue is justified. Keeping the existing execution model or
adding only a small admission limit is a valid outcome. AgentD server-command
orchestration remains outside this task.

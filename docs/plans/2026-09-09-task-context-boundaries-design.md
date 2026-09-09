# Task Context Boundaries Design

## Goal

Prevent completed Orion tasks and review findings from contaminating later work
while preserving the user-visible completion record and the durable repository
state needed to continue safely.

## Boundary

A worker may be reused only while its one bounded task is still active, including
review fixes, commit preparation, integration, and cleanup. Once that task is
verified complete, no later task may send follow-up work to that worker. Every
later task receives a fresh subagent with no inherited conversation.

The primary agent cannot require the runtime to erase messages from its own
conversation. Instead, it applies an operational reset: after reporting the
completed task, it reconstructs the next task's working context from current
repository evidence and treats task-specific reasoning from the completed task
as stale.

## Durable Handoff

Before the reset, make the completed result durable in the locations owned by
the workflow: commits, task-tree cleanup, and `MODULE_REVIEW.md` updates. Then
show the user a compact completion report containing:

- what was solved;
- how it was solved;
- changed parts and the change in each;
- verification results and remaining risks or work;
- concrete next steps that can be taken.

The next context is rebuilt from the current `HEAD`, worktree status, active task
tree, applicable plans and rules, relevant `MODULE_REVIEW.md`, unresolved user
decisions, and the still-authorized pool scope. It excludes the completed task's
transient investigation, rejected alternatives, implementation chatter, and old
worker summaries.

## Review-Specific Behavior

Each module audit worker and each repair worker is fresh for its bounded unit.
After a repair lands, the primary agent revalidates and commits the report,
prints the completion report, retires the repair's agents, rereads the current
`MODULE_REVIEW.md`, and rebuilds the repair queue from active findings only.
Resolved findings and their supporting analysis do not enter the next repair's
context. Cross-finding facts are carried forward only when they remain present
as current repository evidence or an unresolved decision.

## Alternatives

- Native context compaction alone is not enforceable by repository skills and
  does not guarantee that completed-task details disappear.
- Starting a new top-level session per task gives stronger physical isolation
  but breaks automatic queue continuation and loses useful authorization and
  blocker state.

The operational reset with fresh bounded workers provides deterministic task
isolation without changing the product runtime.

# Repository definitions

## Workflow-control scope

The **workflow-control scope** is the exact set of repository files that define
or record agent workflow rather than product implementation:

- repository-local skills under `.agents/skills/`;
- the filesystem task tree rooted at `docs/plans/TASK.md`, including
  `current-work/`, `upcoming-work/`, their descendants, and the root `TASKS.md`
  compatibility pointer when it changes as part of task-tree maintenance;
- every module review report named `MODULE_REVIEW.md`.

The scope excludes `AGENTS.md`, ordinary plan documents outside the task tree,
production code, tests, build files, and configuration unless another rule names
them explicitly. Mechanically required references from an in-scope change do not
make their containing files part of the scope.

For repository routing and Maven policy, changes within this scope are
workflow-control changes rather than product implementation. Make and commit
them directly on `main`, never in an implementation branch or worktree and never
through an implementation worker. During queued execution, the primary agent
running `orion-change-orchestrator` owns task-tree state transitions; otherwise
the primary agent owns the in-scope change. Do not run Maven for an in-scope-only
commit, but still run validation specific to affected skill resources. In-scope
changes from the same requested work may share one commit and do not require
isolation from each other.

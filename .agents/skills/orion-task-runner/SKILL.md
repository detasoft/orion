---
name: orion-task-runner
description: >-
  Use for selecting, planning, describing, ordering, claiming, pausing, or
  completing work in the numbered Orion task tree rooted at docs/plans/TASK.md.
---

# Orion Task Runner

Own the filesystem task model and its state transitions. Task-tree execution
uses Change workflow; this skill does not own implementation, tests, or review.

## Startup

Read `AGENTS.md`, `docs/plans/TASK.md`, relevant ancestor `TASK.md` files and
candidate leaves, then inspect `git status --short`. Preserve unrelated work.

Planning, triage, explanation, and status requests do not claim or start work.
Only Change creates an implementation claim. A requested task-tree edit may
still add, reorder, or rewrite nodes because that edit is itself the result.
Any workflow may update task status and completion state when supported by
verified work within the request's scope. State bookkeeping alone does not
select Change or authorize implementation.

## Task model

`docs/plans/TASK.md` is the root. `current-work/` and `upcoming-work/` are
unnumbered queue roots with their own `TASK.md`.

- `NN_slug.md` is an executable leaf.
- `NN_slug/` is a composite described by `NN_slug/TASK.md`; composites may
  nest but are never executable.
- Sibling files and directories share one numeric namespace. Prefixes are
  compared numerically, are unique among siblings, and may have gaps.
- Filesystem entries are the only source of child membership and order. Parent
  files describe aggregate scope, dependencies, and acceptance, but do not
  duplicate child checklists.
- Detailed designs belong in ordinary `docs/plans/` documents. `TASKS.md`, if
  present, is only a pointer to the root task.

Completion removes the executable leaf instead of adding a completed status.
Remove an empty composite only when its aggregate acceptance and remaining scope
are satisfied. Never remove queue roots or the root task.

## Selection

For a generic next-task request, traverse `current-work/` recursively in
numeric sibling order, then `upcoming-work/` if no current leaf is ready. Select
the first dependency-ready leaf without an `Owner:` entry. An explicit user task
or pool narrows the candidates but does not bypass dependencies or ownership.

Only an `Owner:` entry in the executable leaf's body is a claim. Ignore
`Status:`, ownership prose, review findings, and parent-composite owner markers
when deciding whether that leaf is claimed. Never take over an existing leaf
claim unless the user explicitly names it and authorizes takeover. An old
timestamp does not release a claim, and a missing task file does not prove
integration or completion.

Numeric order is selection priority, not an implicit dependency. Skip a blocked
leaf only for genuinely independent ready work. Report the exact blocker when no
ready leaf remains.

## Planning and insertion

Insert new work at its intended local execution position. Prefer a free numeric
gap; do not renumber to close gaps after deletion. If insertion requires
renumbering, change only necessary siblings and update their references
together.

Use a leaf for one bounded executable task and a composite for aggregate scope.
When splitting a leaf, move all executable scope into numbered children and keep
only aggregate context in the composite. Do not leave executable scope in both.

Do not create speculative siblings, duplicate production paths, or task nodes
merely to route a Quick or Simple change. When the canonical workflow selection
requires Change and no matching leaf exists, create only the minimal executable
leaf needed for its isolated ownership. This exception also covers skills and
ordinary documentation when isolation requires Change; file type alone never
justifies a task. An existing leaf may include those files as explicit deliverables.

## Change claim

Executing any task-tree leaf selects Change workflow. Before worker launch, the
primary agent records one claim in that leaf and commits it directly on `main`:

```markdown
- Owner: codex, session SESSION_ID, branch `codex/CHANGE_SLUG`,
  worktree `.worktrees/CHANGE_SLUG`, started YYYY-MM-DD HH:MM Europe/Amsterdam.
```

Use the actual session ID when available and collision-free branch/worktree
names. The exact committed `main` HEAD containing the claim and governing
inputs is the worker base. Never place the claim in a parent composite,
`MODULE_REVIEW.md`, a task branch, or the worker worktree.

A matching leaf claim blocks duplicate execution. The worker treats the claim,
pause, and completion state governing its task as read-only.

## Pause and completion

For an incomplete Change execution, the worker reports its next step without
editing the task tree. The primary agent updates the existing leaf claim and
commits it directly on `main`:

```markdown
- Owner: codex, session SESSION_ID, branch `codex/CHANGE_SLUG`,
  worktree `.worktrees/CHANGE_SLUG`, paused YYYY-MM-DD HH:MM Europe/Amsterdam;
  next: brief next step.
```

Complete task state only when the requested scope and acceptance have been
satisfied with the verification and review required by the workflow that
performed the work. For Change, integration, verification on `main`, and removal
of its worktree and branch must also be complete before the primary agent
commits completion state directly on `main`.

Quick and Simple may record completion in their current worktree and branch,
including in the same commit as their verified result; they do not acquire
Change's worktree or integration requirements. Delete the completed leaf,
remove eligible empty ancestors, and update outstanding-work and dependency
references with completion evidence. Preserve claims owned by other executions;
do not release or take them over merely to synchronize status.

Preserve unfinished siblings and claimed state. Do not renumber after completion
or retain completed task nodes as history.

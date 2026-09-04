---
name: orion-task-runner
description: >-
  Coordinate Orion repository work from the filesystem task tree rooted at
  docs/plans/TASK.md. Use when the user asks Codex to take, choose, continue,
  claim, or run a task from the Orion task list, including prompts like "возьми
  задачу", "следующая задача", "продолжай по задачам", "pick a task", or
  requests that rely on current high-level tasks.
---

# Orion Task Runner

## Overview

Use this skill to choose Orion work from the filesystem task tree rooted at
`docs/plans/TASK.md`, mark it as owned before substantial edits, execute it
under the repository rules, commit the task claim immediately, and keep task
tracking current without turning it into a detailed plan.

## Startup

1. Confirm the working directory is the Orion repository.
2. Read `AGENTS.md`, `docs/plans/TASK.md`, relevant child `TASK.md` files, and
   `git status --short` before choosing work.
3. If root `TASKS.md` exists, treat it as a compatibility pointer only, not as
   task state.
4. Treat existing uncommitted changes as user-owned unless you made them in the
   current request. Do not revert or stage unrelated changes.
5. If the user asks only for status, triage, or explanation, do not claim a task.

## Task Selection

Tasks are hierarchical. Treat every directory with a `TASK.md` as a task node.
A parent task can contain multiple ready next tasks; do not collapse the next
step into a single item when several independent child tasks are available.

First classify tasks as claimed or unclaimed. Treat a task as claimed when its
`TASK.md` has any of these signals:

- any `Owner:` line, including `Owner: codex`;
- an explicit activity marker such as `Active next task`, `Current work`, `in
  progress`, `started`, or `paused`;
- checked child tasks under an otherwise unchecked parent task,
  unless the block explicitly says the work is available.

Claimed means occupied by another session or person. Never select, continue,
update, or replace the owner of a claimed task unless the user explicitly names
that task and asks to take it over.

### Simplification Priority

After honoring the user's explicit task or pool, ownership, and dependency
order, prefer a dependency-ready current-work leaf whose primary result is safe
deletion or consolidation over an additive leaf at the same selection stage.
This preference takes precedence over parent-file order. Treat removal as safe
when it preserves required runtime behavior and every explicitly preserved
wire or persisted contract.

Plan replacements as an atomic move to one canonical production path. Update
all real in-repository consumers and delete the old internal API, state,
configuration, branch, and legacy-only tests in the same task. Do not add or
retain deprecated aliases, adapters, compatibility shims, dual read/write
paths, migration modes, feature flags, or fallback paths for the replaced
model. Tests and hypothetical future or external consumers do not justify a
second production path.

When a persisted or wire contract must remain stable, preserve it through the
single new implementation rather than keeping the old internal API. If direct
deletion would remove required current functionality, keep that functionality
in the canonical path; do not solve the conflict by preserving two ways to do
the same operation.

For a generic request such as "take a new task", prefer this order:

1. The first unclaimed unchecked task under `docs/plans/current-work/`.
2. The first unclaimed unchecked child task under an active parent task.
3. The first unclaimed unchecked task under `docs/plans/upcoming-work/`, moved
   under current work only when starting it.

When the user explicitly names or describes a task, select it only if it is
unclaimed. Ask a concise question before proceeding when two or more unclaimed
tasks match equally well, when the requested work is absent from the task
tracking files, when the named task is claimed, or when no unclaimed task
remains.

## Task Storage

Use `docs/plans/TASK.md` as the root task index. Use directories as task nodes
and store each task's details in that directory's `TASK.md`. A directory can be
both a task and a container for subtasks. Keep parent `TASK.md` files focused on
status, scope, ownership, and the list of immediate child tasks; put detailed
implementation notes in ordinary `docs/plans/*.md` plan files only when they
are broader than the task node.

Do not recreate a central `TASKS.md` list. If root `TASKS.md` exists, leave it
as a compatibility pointer to `docs/plans/TASK.md`.

## Claim Format

Before substantial code, doc, or test edits, update only the selected unclaimed
task node's `TASK.md`:

```markdown
- [ ] Task title and short context.
  - Owner: codex, session SESSION_ID, started YYYY-MM-DD HH:MM Europe/Amsterdam.
```

Use the current local date and time, and record a stable identifier for the
current Codex session instead of relying on the timestamp alone. Use the actual
session identifier when it is available; otherwise generate a short unique local
session id once and reuse it for all owner lines written by this session. An
existing owner line means the task is claimed; do not update it without an
explicit user-requested takeover. Keep the file high-level; put detailed design
or implementation notes in `docs/plans/`.

## Claim Commit

Immediately after claiming the task, create a documentation-only commit before
starting implementation. Include only the `TASK.md` and other `docs/` changes
made to start that task, such as moving its task node under current work. Do not
stage unrelated or pre-existing changes, including other edits in `docs/`.

Use a concise, single-line commit message that describes starting or claiming
the selected task. Do not run tests for this documentation-only commit. If the
claim changes cannot be isolated safely from existing edits, stop and ask the
user how to proceed instead of committing mixed changes.

## Execution Rules

1. Follow `AGENTS.md` for Maven profiles, commit behavior, tests, comments, and
   task tree scope.
2. Read any referenced plan under `docs/plans/` before changing related code.
3. Add or extend tests when changing functionality.
4. Run focused verification during implementation and the appropriate Maven check before claiming completion.
5. Keep task tree edits scoped to the selected task and a small number of upcoming high-level tasks.

## Finish

When the selected task is fully implemented and verified, mark it complete and
remove the owner line. Add or adjust the next high-level task only if needed.
When the task was completed in a dedicated Git worktree, follow the dedicated
worktree completion rules in `AGENTS.md` instead of retaining a completed task
node.

When stopping with work incomplete, keep the task unchecked and replace or
update the owner line with a short status line:

```markdown
  - Owner: codex, session SESSION_ID, paused YYYY-MM-DD HH:MM Europe/Amsterdam; next: brief next step.
```

When closing a completed task, include both fields explicitly in the final
response:

```text
Task: <task name>
Path: <path to the task's TASK.md>
```

Also summarize code changes, list verification run, and mention any unrelated
pre-existing working tree changes.

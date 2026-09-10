---
name: orion-quick-workflow
description: >-
  Use when an Orion change is small enough for a prompt direct edit, optional
  executor-chosen verification, and immediate commit, commonly documentation,
  task-tree, skill, Makefile, or similarly low-risk maintenance.
---

# Orion Quick Workflow

Make one coherent change directly on `main` and commit it promptly, without a
routing task, worker, test, or review ceremony.

## Selection and boundary

Read `AGENTS.md` and [the canonical quick workflow](../../../docs/definitions.md#quick-workflow).
The listed file types are defaults, not eligibility rules. This workflow may
change any file when the executor judges its direct mechanics proportionate.
Documentation, task-tree and skill edits normally start here; a Makefile or
source change may also use it. A large or risky documentation change may use the
simple or change workflow instead.

Use [orion-task-runner](../orion-task-runner/SKILL.md) when the requested edit
changes task-tree content or state. Do not create, claim, move, pause, complete,
or delete a task merely to route a quick change. A requested task-tree operation
may itself do those things because the operation is the requested result.

If evidence shows that useful completion requires test coverage, extensive
verification, staged user review, isolated worker ownership, or unresolved
design decisions, stop before expanding the change and select the workflow whose
mechanics fit. File type alone never forces that decision.

## Quick reference

| Situation | Required action |
| --- | --- |
| Direct mechanics remain proportionate | Edit and commit on `main`. |
| One coherent change crosses file categories | Keep it in one commit. |
| Requested task-tree operation | Apply `orion-task-runner` directly. |
| Another workflow reaches a primary-owned state milestone | Commit it at that moment. |
| A useful check exists | Run it at the executor's discretion. |
| No meaningful automated check exists | Inspect the diff and commit without inventing one. |

## Execute

1. Inspect `git status --short` and the requested area. Preserve unrelated
   staged and unstaged changes.
2. Identify one coherent state change. Include directly related files in the
   same commit even when they cross documentation, workflow-control, build, or
   source categories. Keep genuinely unrelated changes separate.
3. Make only the requested edits. Do not create routing documentation, launch a
   worker, create a branch or worktree, or introduce an implementation checkpoint.
4. Briefly inspect the complete diff for correctness and consistency. At the
   executor's discretion, run any already available check whose value is
   proportionate to the change. This may be a lightweight artifact validator,
   `git diff --check`, a Makefile dry run, or a Maven or other project test.
5. Stage only the coherent owned change, verify that no unrelated files would
   enter the commit, and create one descriptive single-line commit immediately.
   Do not add a user-review gate unless the user explicitly asks for one.

Do not add tests, test coverage, implementation scaffolding, or source-scanning
checks solely for this workflow. No pre-commit or post-commit command is
mandatory. Do not broaden verification just to report a check or repeat the
same check after commit when it already passed against the committed content.
When no useful automated check exists, diff inspection is sufficient; report
that no automated check was applicable.

## State milestones inside other workflows

When a simple or change workflow reaches a primary-owned task, plan, review, or
other repository-state milestone, that surrounding workflow decides when the
state is accurate and which files belong to it. Perform the edit, quick useful
check, and prompt commit without adding a worker handoff, user interaction,
approval gate, or split by file category. Then return to the surrounding
workflow.

Keep primary-owned lifecycle state separate from a worker implementation commit.
Examples include a confirmed review finding before repair, task creation or
claim before worker launch, a governing-plan correction before worker resume,
task completion after verified integration, and removal of a resolved finding.

## Red flags and common mistakes

- Selecting or rejecting this workflow only because of a file extension.
- Creating a task solely to route the quick change.
- Splitting one coherent cross-category change into multiple commits.
- Treating any check as mandatory merely because this workflow was selected.
- Adding tests or running Maven as ceremony rather than for useful evidence.
- Inventing a validator when a quick useful check does not exist.
- Adding an approval gate that changes the surrounding workflow's interaction.

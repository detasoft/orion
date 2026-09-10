---
name: orion-document-workflow
description: >-
  Use when an Orion change affects only documentation, repository rules,
  skills, task-tree state, or MODULE_REVIEW.md reports, including primary-owned
  documentation updates reached during another workflow.
---

# Orion Document Workflow

Make a coherent documentation-only change directly and commit it promptly,
without implementation routing or test ceremony.

## Boundary

Read `AGENTS.md` and [the canonical document workflow](../../../docs/definitions.md#document-workflow).
Work directly on `main` as the primary agent. Use
[orion-task-runner](../orion-task-runner/SKILL.md) when the requested edit
changes task-tree content or state, but do not launch an implementation worker.

This workflow applies only while the complete change remains documentation-only.
If the required result needs source, tests, build files, configuration, generated
artifacts, or runtime verification, stop and select the simple or change workflow
before making that implementation change.

Do not create, claim, move, pause, complete, or delete a task merely to route
document work. A requested task-tree operation may itself do those things; the
operation is the document change, not bookkeeping for another implementation.

## Quick reference

| Situation | Required action |
| --- | --- |
| Ordinary or workflow-control docs only | Edit and commit directly on `main`. |
| One coherent change crosses documentation categories | Keep it in one commit. |
| Requested task-tree operation | Apply `orion-task-runner` directly. |
| A surrounding workflow reaches a documentation milestone | Commit it at that moment. |
| No meaningful automated check exists | Inspect the diff and commit without inventing one. |

## Direct document change

1. Inspect `git status --short` and the requested documents. Preserve unrelated
   staged and unstaged changes.
2. Identify one coherent documentation state. Include directly related ordinary
   docs, task descriptions, rules, plans, reports, and skills in the same commit
   even when they cross workflow-control scope. Keep genuinely unrelated changes
   separate.
3. Make only the requested edits. Do not create a routing task or plan, launch a
   worker, create a branch or worktree, or introduce an implementation checkpoint.
4. Briefly inspect the complete diff for factual consistency and broken local
   references. Run only an already available, quick check that is meaningful for
   the edited artifact: for example `git diff --check`, the skill validator for
   a changed skill, a task-tree structural check after a task edit, or an
   existing link check when links changed.
5. Stage only the coherent owned change, verify that no unrelated files would
   enter the commit, and create one descriptive single-line commit immediately.
   Do not add a user-review gate unless the user explicitly asks for one.

Do not add tests, test coverage, production scaffolding, or source-scanning
checks for documentation. Do not run Maven, project tests, or post-commit tests.
Do not broaden verification just to report a check. When no useful automated
check exists, the diff inspection is sufficient; say that no automated check
was applicable.

## Documentation milestones inside other workflows

When a simple or change workflow reaches a primary-owned documentation milestone,
that surrounding workflow decides when the state is accurate and which files
belong to it. Perform the edit, quick meaningful check, and prompt commit without
adding a worker handoff, user interaction, approval gate, or extra split by
document category. Then return to the surrounding workflow.

Keep lifecycle documentation separate from an implementation commit. Examples
include a confirmed review finding before repair, task creation or claim before
worker launch, a governing-plan correction before worker resume, task completion
after verified integration, and removal of a resolved review finding.

## Red flags and common mistakes

- Creating a task solely because documentation changed.
- Splitting one coherent task-description and ordinary-doc update into two commits.
- Running Maven or adding tests for Markdown-only behavior.
- Inventing a validator when a quick relevant check does not exist.
- Adding an approval gate that changes the surrounding workflow's interaction.

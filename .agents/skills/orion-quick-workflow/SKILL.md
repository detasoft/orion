---
name: orion-quick-workflow
description: >-
  Use when an Orion change has one understood coherent result and does not need
  sequential checkpoints or isolated worker ownership.
---

# Orion Quick Workflow

Produce one logical commit directly in the current worktree and branch.

## Boundary

Read `AGENTS.md` and
[the canonical workflow and verification rules](../../../docs/definitions.md#repository-workflows).
Quick may change any file; the shape of the result, not its file type, selects
the workflow. A mutating request performed through Quick authorizes its commit.

The requested result may require several internal steps, including tests, but
they form one checkpoint and one commit. If the request contains several
independently committable results, needs staged user review, or requires an
isolated worker, stop before expanding the edit and report that Quick no longer
fits.

Quick never launches a worker, creates a worktree, or switches branches. It may
update task status and completion state through `orion-task-runner` when that
update records verified work in scope or is itself the requested result. Such
bookkeeping does not start task-tree implementation or select Change.

## Execute

1. Inspect `git status --short` and the requested area. Preserve all unrelated
   staged and unstaged changes.
2. Identify the complete coherent result and make only those edits.
3. Keep the result unstaged and run every check required by the
   [pre-commit verification table](../../../docs/definitions.md#pre-commit-verification).
   Add or update tests whenever behavior changes.
4. Review the complete result against the request and applicable repository
   rules. Fix local findings and rerun affected checks before staging.
5. Stage only the verified files with `git add -- <owned-paths>`. Inspect
   `git diff --cached -- <owned-paths>` and run
   `git diff --cached --check -- <owned-paths>`.
6. Immediately create the commit with
   `git commit --only -m "<single-line subject>" -- <owned-paths>` using the same
   explicit file list. Never use a bare `git commit` or `git commit -a`.
   `--only` takes whole working-tree files, not selected staged hunks, so the
   selected files must contain only this result's changes. Unrelated staged
   files may remain in the index. Do not ask for additional review or commit
   approval.

Do not include unrelated work merely because it is already staged or because
the requested result spans several file categories. If a required check fails,
do not commit; report the failure when it cannot be fixed in scope.

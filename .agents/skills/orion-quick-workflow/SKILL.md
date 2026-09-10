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

Quick never creates or claims a task, launches a worker, creates a worktree, or
switches branches. Use `orion-task-runner` only when changing task-tree content
is itself the requested result.

## Execute

1. Inspect `git status --short` and the requested area. Preserve all unrelated
   staged and unstaged changes.
2. Identify the complete coherent result and make only those edits.
3. Stage only the result's files and hunks. Inspect `git status --short`,
   `git diff --cached`, and `git diff --cached --check`.
4. Run every check required by the
   [pre-commit verification table](../../../docs/definitions.md#pre-commit-verification).
   Add or update tests whenever behavior changes.
5. Review the complete staged result against the request and applicable
   repository rules. Fix local findings, restage, and rerun affected checks.
6. Create one descriptive single-line commit immediately. Do not ask for an
   additional review or commit approval.

Do not include unrelated work merely because it is already staged or because
the requested result spans several file categories. If a required check fails,
do not commit; report the failure when it cannot be fixed in scope.

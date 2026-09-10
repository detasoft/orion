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

1. Inspect `git status --short`, `git diff --cached --name-status`, and
   `git diff --cached` before editing. Record which changes were already staged;
   do not assume ownership of them. Preserve all unrelated staged and unstaged
   changes.
2. Identify the complete coherent result and make only those edits.
3. Keep the result unstaged and run every check required by the
   [pre-commit verification table](../../../docs/definitions.md#pre-commit-verification).
   Add or update tests whenever behavior changes.
4. Review the complete result against the request and applicable repository
   rules. Fix local findings and rerun affected checks before staging.
5. Recheck the entire index before staging: other sessions may have staged work
   since step 1. Select an explicit list of files owned entirely by this result.
   `git commit --only` takes their working-tree contents, not just staged hunks.
   If any selected file contains unrelated staged or unstaged changes, stop
   before staging and report that Quick cannot safely commit the mixed file.
   Never reset, stash, or unstage somebody else's work to make the index empty.
6. Stage only the verified files with `git add -- <owned-paths>`. Inspect
   `git diff --cached -- <owned-paths>` and run
   `git diff --cached --check -- <owned-paths>`. Check the proposed commit with
   `git commit --only --dry-run -- <owned-paths>`; its commit list must contain
   only the intended result. Use explicit file paths, not directories or globs.
7. Immediately create the commit with
   `git commit --only -m "<single-line subject>" -- <owned-paths>` using the same
   file list. Never use a bare `git commit` or `git commit -a`, even when the
   initial index was empty. Do not ask for additional review or commit approval.
8. Inspect `git show --stat --oneline HEAD` and `git diff --cached` to confirm
   the commit scope and that unrelated staged changes remain. If another
   session changed the index, investigate without restoring a stale snapshot.

Do not include unrelated work merely because it is already staged or because
the requested result spans several file categories. If a required check fails,
do not commit; report the failure when it cannot be fixed in scope.

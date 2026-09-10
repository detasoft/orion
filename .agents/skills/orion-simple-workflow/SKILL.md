---
name: orion-simple-workflow
description: >-
  Use when an Orion change contains several sequential checkpoints or requires
  staged user review in the current worktree.
---

# Orion Simple Workflow

Complete checkpoints one at a time in the current worktree and branch. Every
checkpoint is verified, automatically reviewed, presented to the user, committed,
and compacted before the next checkpoint starts.

## Boundary

Read `AGENTS.md`,
[the canonical workflow and verification rules](../../../docs/definitions.md#simple-workflow),
`docs/reviews/RULES.md`, and
[orion-minimal-implementation](../orion-minimal-implementation/SKILL.md).

Simple never creates or claims a task, manages lifecycle or governing state,
launches a worker, creates a worktree, or switches branches. If the work no
longer needs sequential checkpoints or staged review, stop and reselect through
the canonical workflow table. Task-tree execution and isolated ownership are
outside this workflow.

Only one checkpoint may be unfinished. Do not begin or edit a later checkpoint
until the current checkpoint has been committed and its context compacted.

## Checkpoint cycle

1. Inspect `git status --short`, the relevant files, real consumers, and local
   rules. Preserve unrelated staged and unstaged changes.
2. Choose one coherent checkpoint that leaves the repository working. State its
   result, preserved behavior, affected path, and remaining checkpoints.
3. Apply `orion-minimal-implementation`, implement only this checkpoint, and add
   or update tests whenever behavior changes. When repairing a
   `MODULE_REVIEW.md` finding, update or remove the finding in this same
   checkpoint.
4. Stage only the checkpoint. Inspect `git status --short`,
   `git diff --cached`, and `git diff --cached --check`.
5. Run every check required by the
   [pre-commit verification table](../../../docs/definitions.md#pre-commit-verification).
6. Automatically review the staged result against the request,
   `docs/reviews/RULES.md`, applicable `@AiRule` comments, and
   `orion-minimal-implementation`. Fix task-local findings, restage, and rerun
   affected checks.
7. Present the stable task name, proposed subject, staged checkpoint,
   verification summary, automatic-review result, remaining checkpoints, and
   unrelated workspace state. Ask the user to review it unless an explicit
   commit instruction already covers this checkpoint.
8. An explicit `commit` instruction means user review is complete. Recheck that
   the index is the reviewed result and commit immediately using:

   ```text
   <stable task name>: <imperative checkpoint summary>
   ```

   Keep the task-name prefix byte-for-byte identical across the sequence.
9. Compact context before selecting the next checkpoint.

If the user requests corrections instead of committing, update only the current
checkpoint, restage it, rerun affected verification and automatic review, and
present it again. Never commit an older staged version while newer corrections
exist.

## Context compaction

Retain only:

- the original request or module and the remaining checkpoint queue;
- still-applicable user decisions and cross-checkpoint constraints;
- current `HEAD`, workspace state, and unrelated changes;
- each completed checkpoint's commit SHA, short result, and
  `command -> passed/failed` verification summary.

Remove:

- detailed investigation and intermediate tool output for completed checkpoints;
- raw test output;
- local implementation decisions and rejected alternatives that cannot affect
  remaining work;
- correction discussion and implementation detail already captured by the commit.

Use native context compaction when available. Otherwise reconstruct the
working set from the retained facts before proceeding.

## Completion

A checkpoint is complete only after its required checks, automatic review, user
review or covering commit instruction, commit, and context compaction. A
multi-checkpoint Simple run is complete only when every checkpoint has passed
that cycle.

Do not repeat a check that passed against identical committed content. Run a
post-commit check only when it validates the commit environment itself or could
not run before commit.

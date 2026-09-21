---
name: orion-simple-workflow
description: >-
  Use when work contains sequential checkpoints or requires staged user review
  in the current worktree.
---

# Orion Simple Workflow

Complete checkpoints one at a time in the current worktree and branch. Obtain
user agreement on each proposed checkpoint before implementation, then approval
of its verified staged result before committing. After the commit and context
compaction, immediately present the next checkpoint for discussion.

## Boundary

Read `AGENTS.md`,
[the canonical workflow and verification rules](../../../docs/definitions.md#simple-workflow),
`docs/reviews/RULES.md`, and
[orion-minimal-implementation](../orion-minimal-implementation/SKILL.md).

Simple never launches a worker, creates a worktree, or switches branches.
It may update task status and completion state through `orion-task-runner`,
including in the same checkpoint as the verified result. If the work no
longer needs sequential checkpoints or staged review, stop and reselect through
the canonical workflow table. Isolated worker ownership is outside this workflow.

Only one checkpoint may be unfinished. Do not begin or edit a later checkpoint
until the current checkpoint has been committed and its context compacted.

## Checkpoint cycle

1. Inspect `git status --short`, the relevant files, real consumers, and local
   rules. Preserve unrelated staged and unstaged changes.
2. Propose one coherent checkpoint that leaves the repository working. Explain
   the current problem, concrete scenarios showing why it is worth fixing, the
   expected result, preserved behavior, affected path, and remaining checkpoints.
   Wait for the user to agree to this proposal before implementation. Read-only
   investigation may establish the proposal; agreement to the overall task does
   not replace this checkpoint discussion. Do not repeat approval already given
   for this concrete proposal.
3. Apply `orion-minimal-implementation`, implement only this checkpoint, and add
   or update tests whenever behavior changes. When repairing a
   `MODULE_REVIEW.md` finding, update or remove the finding in this same
   checkpoint.
4. Keep the checkpoint unstaged and run every check required by the
   [pre-commit verification table](../../../docs/definitions.md#pre-commit-verification).
5. Automatically review the complete result against the request,
   `docs/reviews/RULES.md`, applicable `@AiRule` comments, and
   `orion-minimal-implementation`. Fix task-local findings and rerun affected
   checks before staging.
6. Stage only the ready checkpoint. Inspect `git status --short`,
   `git diff --cached`, and `git diff --cached --check`.
7. Present the stable task name, proposed subject, staged checkpoint,
   verification summary, automatic-review result, remaining checkpoints, and
   unrelated workspace state. Wait for approval of this staged result unless an
   explicit commit instruction already covers the checkpoint. Approval to start
   implementation does not authorize committing the resulting changes.
8. Approval of the presented staged result, including an explicit `commit`
   instruction, authorizes the commit. Recheck that the index is the reviewed
   result and commit immediately using:

   ```text
   <stable task name>: <imperative checkpoint summary>
   ```

   Keep the task-name prefix byte-for-byte identical across the sequence.
9. Compact context and immediately prepare and present the next checkpoint as
   in steps 1–2. Do not ask a separate question about whether to continue or wait
   for another continuation message. Still obtain agreement on the next proposal
   before implementing it; the previous result approval covers only its commit.

If the user requests corrections instead of committing, unstage only the current
checkpoint, correct it, rerun affected verification and automatic review, then
restage and present it again. Never commit an older staged version while newer
corrections exist.

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

A checkpoint is complete only after agreement on its proposal, implementation,
required checks, automatic review, approval of the staged result or a covering
commit instruction, commit, and context compaction. A
multi-checkpoint Simple run is complete only when every checkpoint has passed
that cycle.

Do not repeat a check that passed against identical committed content. Run a
post-commit check only when it validates the commit environment itself or could
not run before commit.

---
name: orion-simple-workflow
description: >-
  Use when an Orion change benefits from direct current-worktree implementation,
  logical staged checkpoints, self-review, and explicit user approval before
  each commit, commonly bounded predictable fixes and refactorings.
---

# Orion Simple Workflow

Implement one small, understood task in the current worktree. Choose coherent
commit checkpoints as the work proceeds, and leave each verified checkpoint
staged for user review before committing it.

## Boundary

Read `AGENTS.md`, [the canonical workflow definitions](../../../docs/definitions.md#simple-workflow),
and [orion-minimal-implementation](../orion-minimal-implementation/SKILL.md).
Use the workflow definitions as guidance and choose this workflow when its
checkpoint and user-review mechanics fit the actual work. Within `orion-review`,
consider this workflow first, then decide from the repair's magnitude and risk.

Do not create or claim a task merely to route the change, write a routing plan,
launch an implementation worker, or create a branch or worktree. File type and
queue membership do not select or reject this workflow.

If inspection reveals scale, uncertainty, or risk that makes another workflow's
mechanics more proportionate, stop before expanding the change. Preserve and
report the current workspace state; do not silently turn partially edited files
into a change-workflow base.

## Quick reference

| State | Required action |
| --- | --- |
| Simple task starts | Choose one stable task name and the next logical checkpoint. |
| Boundary fails during inspection | Stop and report; do not expand the edit. |
| Checkpoint is coherent and verified | Stage it, self-review it, and request user review. |
| User requests corrections | Revise, verify, restage, and request review again. |
| User approves | Commit immediately; do not repeat a passed check. |

## Implement and verify

1. Inspect `git status --short`, the relevant files, real consumers, and local
   rules. Preserve every unrelated staged and unstaged change.
2. Derive a concise imperative task name from the user's wording or intended
   outcome. Keep it byte-for-byte identical throughout the task. It identifies
   related commits; it does not create a task-tree node or claim.
3. Choose the next logical checkpoint from the work now understood. A
   checkpoint must express one coherent change, be independently reviewable,
   and leave the repository working. Discover and adjust later checkpoints as
   implementation evidence changes; no routing plan is required.
4. State the checkpoint result, preserved behavior, affected path, and why the
   task remains simple. Apply `orion-minimal-implementation`; add or update tests
   when functionality changes.
5. Implement only that checkpoint directly in the current worktree. Include
   ordinary product documentation needed to describe its changed behavior.
6. Run the focused and development verification required by `AGENTS.md`.

Do not split checkpoints mechanically by file or line count. Do not combine
distinct coherent checkpoints merely to ask the user for review fewer times. A
task with only one logical checkpoint still follows the same gate once.

## Staged user-review gate

Stage only files and hunks produced for the checkpoint. Inspect
`git status --short`, `git diff --cached`, and `git diff --check`; confirm that the index
contains the complete intended checkpoint and no unrelated state. Self-review
the staged diff against the user task, `docs/reviews/RULES.md`, applicable
`@AiRule` comments, and `orion-minimal-implementation`. Fix task-local findings,
verify again, and replace the staged result before presenting it.

Present the stable task name, proposed commit subject, staged checkpoint,
verification results, self-review outcome, remaining checkpoints, and any
unrelated workspace state to the user. Ask the user to review it and stop
without committing. Urgency, a one-line diff, successful tests, or a request to
avoid interruptions never bypasses a real checkpoint gate.

If the user requests corrections, edit, verify, self-review, and replace the
staged result as needed. Present the new staged diff and ask for review again.
Do not commit an earlier staged version while newer unstaged corrections exist.

After explicit approval, recheck that the index is exactly the reviewed result
and that no unrelated staged changes would enter the commit. Before editing or
starting any later checkpoint, immediately create a one-line commit with this
exact structure:

```text
<task name>: <imperative checkpoint summary>
```

For example, related checkpoints may be `Simplify test launcher: Rename private
helper` and `Simplify test launcher: Remove redundant Maven property`. Keep the
task-name prefix identical and make the suffix describe that checkpoint's
actual delta. This makes the commits discoverable and squashable by subject;
do not rewrite or squash approved commits unless the user asks.

Do not repeat verification that already passed against the identical staged
checkpoint. Run a post-commit check only when it validates the commit itself or
could not run before commit. If that check requires a fix commit, use the exact
same subject as required by `AGENTS.md`. Then select the next logical checkpoint.
Approval of one staged checkpoint authorizes only its commit, not later
checkpoints, edits, or unrelated files.

## Documentation owned by surrounding workflows

This workflow creates no routing documentation. A surrounding workflow may
separately own a governing or workflow-control document, such as a confirmed or
resolved `MODULE_REVIEW.md` finding. The primary agent commits that document on
`main` through `orion-quick-workflow` when its statement becomes accurate,
outside the staged implementation gate. Do not fold it into the implementation
commit merely because its timing falls before or after this workflow.

## Red flags and common mistakes

- Selecting or rejecting this workflow only because of file type or task state.
- Creating a task or plan only to route a qualifying simple change.
- Committing before the user approves the staged result.
- Delaying an approved checkpoint commit while starting later edits.
- Repeating an already passed check solely because the checkpoint was committed.
- Treating approval of an older staged diff as approval of later corrections.
- Hiding a coherent checkpoint to reduce the number of user reviews.
- Changing the task-name prefix between checkpoint commits.
- Absorbing unrelated staged files into the implementation commit.

---
name: orion-change-workflow
description: >-
  Suits for complex tasks with multiple automated reviews and feedback.
---

# Orion Change Workflow

Execute one claimed task-tree leaf through a dedicated worker, branch, and
worktree. Review one final task commit, integrate it when authorized, verify
`main`, and remove the completed execution state.

## Required inputs and roles

Read `AGENTS.md`, `docs/reviews/RULES.md`,
[the canonical Change workflow](../../../docs/definitions.md#change-workflow),
[orion-task-runner](../orion-task-runner/SKILL.md), and
[orion-minimal-implementation](../orion-minimal-implementation/SKILL.md).

The primary agent:

- selects or creates the executable leaf and owns its claim, pause, governing
  inputs, and completion state on `main`;
- coordinates the worker, reviews its complete result, and communicates with the
  user;
- never edits worker deliverables or review fixes.

One fresh implementation worker owns the dedicated task branch/worktree,
explicit deliverables, tests, review fixes, final task commit, authorized
integration, and cleanup. The worker never edits task state governing its own
execution. Never run two implementation workers for the same task or reuse the
worker for another leaf.

Preserve unrelated changes, branches, worktrees, claims, and Git operations.

## Claim and launch

Use `orion-task-runner` to resolve the first matching dependency-ready
unclaimed leaf. For a direct request without a matching leaf, create the smallest
one necessary for the requested result. Do not create speculative siblings or a
composite for one leaf.

Choose collision-free branch and worktree names. Record the claim only in the
executable leaf, with owner, session, exact branch/worktree, and start time.
Commit that state directly on `main`; do not invoke another execution workflow.
The exact committed HEAD containing the claim and governing inputs is the worker
base.

Spawn one fresh worker with the same model as the primary agent, reasoning
effort `high`, and no inherited conversation or the smallest supported bounded
fork. Supply only the task, ancestors and governing plans, base SHA, recorded
branch/worktree, applicable rules, and return contract.

The worker must:

1. Read the supplied task context, `AGENTS.md`, `docs/reviews/RULES.md`,
   applicable `@AiRule` comments, and `orion-minimal-implementation`.
2. Create the recorded branch/worktree from the exact base, confirm the claim is
   present, and keep task state read-only.
3. Implement the smallest complete task. Include a corresponding
   `MODULE_REVIEW.md` update in the same commit when the task repairs a finding.
4. Keep the result unstaged and run every check required by the
   [pre-commit verification table](../../../docs/definitions.md#pre-commit-verification).
   A failed required check blocks the commit.
5. Automatically review the complete result, fix local findings, and rerun
   affected checks before staging.
6. Stage only the verified result, inspect `git diff --cached` and
   `git diff --cached --check`, then commit immediately. Return base/head SHAs, task path, branch/worktree,
   changed parts, verification summary, risks, remaining work, and the required
   `orion-minimal-implementation` summary. Do not integrate at this stage.

If a governing input is materially incomplete, the worker stops. The primary
agent corrects and commits that input directly on `main`, then the same worker
rebases onto the exact corrected base and resumes.

## Review loop

Review the complete branch diff against its real base. Apply the task, governing
plans, repository rules, `@AiRule` comments, and
`orion-minimal-implementation`. Verify code, tests, consumers, and recorded
commands instead of trusting the worker summary.

Return every actionable finding to the same worker. The worker fixes, verifies,
commits or amends, and returns the result; then review the complete diff again.
The primary agent does not silently repair worker deliverables or run missing
implementation tests for the worker.

After a clean review, have the worker squash all task-unique commits into one:

```text
<imperative summary> [task: <leaf path relative to its queue root>]
```

Review the final squashed diff and prepared SHA again.

## Authorization and integration

An explicit commit instruction in the user request authorizes integration of the
clean reviewed task in the same turn. Do not request the same approval again.

Without a covering commit instruction, stop after clean review. Report the task,
prepared SHA, branch/worktree, verification, risks, and unrelated state, and ask
the user to authorize integration. Approval covers only that reviewed SHA.

Before integration, confirm that the SHA and branch are unchanged and the
worktree is clean. The worker then:

1. cherry-picks the reviewed commit to `main`, never merges it;
2. runs `make test` on `main`;
3. applies the same-subject fix-commit rule for change-caused failures;
4. confirms the transferred delta and clean task worktree;
5. removes only the completed worktree and task branch.

Report conflicts, changed SHAs, unrelated failures, or unsafe cleanup instead of
discarding state.

## Completion and context boundary

Only after verified integration and branch/worktree cleanup does the primary
agent use `orion-task-runner` to delete the completed leaf and eligible empty
ancestors, update required references, and commit that state directly on
`main`.

Do not report completion before the implementation, verification, worktree and
branch cleanup, and task-tree completion are all confirmed.

Retire the worker after this task. Before another task, reconstruct context from
current `HEAD`, worktrees, branches, task tree, plans, active review findings,
and unresolved decisions. Do not carry raw logs, worker conversation, or
rejected alternatives into the next task.

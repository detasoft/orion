---
name: orion-review-orchestrator
description: >-
  Run an Orion task pool sequentially with one gpt-5.6-terra/medium worker in a
  dedicated worktree while the primary agent only coordinates and reviews.
  Use when the user asks for review-gated subagent execution over a task
  subtree, an explicit task list, or tasks introduced by a commit.
---

# Orion Review Orchestrator

## Purpose

Keep the primary agent in coordinator/reviewer mode. Give one leaf task at a
time to a fresh implementation worker, route every review finding back to that
worker, and stop at a user gate before transferring the reviewed commit to
`main`. After the user permits or confirms the transfer, finish integration and
start the next ready task automatically.

Never have two implementation workers active at once. The primary agent may
inspect and orchestrate, but must not implement fixes or edit the task branch.

## Required Repository Guidance

Before selecting work, read and apply:

- `AGENTS.md`;
- `docs/reviews/RULES.md`;
- `docs/plans/TASK.md` and the relevant descendant task nodes;
- `../orion-task-runner/SKILL.md` for task selection, ownership, claim, and
  task-tree rules.

Inspect `git status --short` and `git worktree list --porcelain`. Treat existing
changes, branches, worktrees, and claims as owned by somebody else unless this
workflow created them. Check candidate task files in every existing worktree so
a branch-local owner line still counts as a claim. Also inspect the candidate
path, moved-path equivalents, and task identity in relevant local branch refs,
including branches without an attached worktree. Any discovered owner line is
a claim; if ownership cannot be resolved safely, treat the candidate as
blocked. Do not stage, modify, or clean unrelated state.

## Resolve the Pool

Accept any of these pool definitions:

- a task-tree directory or parent `TASK.md`;
- an explicit ordered list of task nodes;
- a commit that introduced task nodes.

For a commit-defined pool, inspect the task files added by that commit and the
current task tree. Choose the newly added aggregate task root, then use its
linked leaf tasks in parent-file order. Treat that order as sequential unless
the task nodes explicitly declare that they are independent. Do not execute the
aggregate parent as a separate implementation task. Follow explicit
`Depends on` relationships before file order, and re-resolve paths if the task
subtree has since moved from upcoming work to current work.

Commit `a40ec0da` identifies the Git interoperability matrix rooted at
`docs/plans/upcoming-work/git-interoperability-matrix`. Its initial leaf order
is harness and state model, reference adapters, Orion adapters, workflow
scenarios, then Maven/CI integration. Current dependency and ownership state
still takes precedence over that historical order. This pool is strictly
sequential even though its original task files do not contain explicit
`Depends on` fields.

Select only an unclaimed, dependency-ready leaf. A claimed prerequisite is a
blocker; do not skip ahead to dependent work. If no ready leaf remains, either
report the pool complete or name the exact blocking task.

## Launch One Worker

Spawn a fresh worker for the selected leaf with:

- model `gpt-5.6-terra`;
- reasoning effort `medium`;
- `fork_turns="none"`, or the smallest supported bounded fork, so the explicit
  model and effort override is applied rather than inherited;
- only the context needed for this task rather than the whole review thread,
  supplied explicitly in the worker prompt.

Tell the worker that it owns every mutation for the task, including task claim,
worktree setup, implementation, tests, commits, review fixes, final squash,
integration after approval, and cleanup. The primary agent owns selection,
review, and user communication only.

The worker must:

1. Read `AGENTS.md`, the selected task node, its parent nodes, referenced plans,
   and applicable local rules.
2. Create a dedicated branch and worktree from the current committed `main`
   HEAD without changing or including the shared working tree. Use a
   collision-free `codex/<task-slug>` branch and `.worktrees/<task-slug>` path,
   unless an existing workflow-owned branch and worktree are being resumed.
3. Inside that worktree, claim the selected task according to
   `orion-task-runner`. Make the isolated documentation-only claim commit before
   implementation and do not include unrelated files. When starting upcoming
   work, perform the required task-tree move as part of that isolated
   start/claim change. Do not put this pre-review claim commit directly on
   `main`; it will be included in the branch's final squash.
4. Until the user gate, perform every subsequent command and edit in that
   worktree. Preserve all unrelated shared-workspace state.
5. Implement production behavior and tests under `AGENTS.md`, run focused
   checks while developing, and run the required development verification.
6. Commit its work and return the task path, worktree, branch, base and head
   SHAs, changed-file summary, verification commands and results, and any known
   risks. It must not transfer the task commit to `main` yet.

If an isolated claim cannot be made safely, the worker must stop without
claiming or editing implementation files and report why.

## Review Loop

Wait for the worker, then review the complete branch diff against its real
base. Apply `docs/reviews/RULES.md`, relevant `@AiRule` class comments, the task
contract, and repository conventions. Check implementation and tests, not only
the worker summary.

Do not run Maven verification solely for review. The worker owns verification;
the primary agent may identify missing, inadequate, or failed checks and send
that as a finding.

For every actionable finding:

1. Report concrete evidence with file and line references and the required
   behavior.
2. Send all findings to the same worker with a follow-up task.
3. Wait for its fixes and verification.
4. Re-review the entire resulting diff, including earlier fixed areas.

Repeat until every reported finding is resolved and no blocking finding
remains. Never silently repair the branch from the primary thread. If the
worker reaches a genuine blocker, stop and ask the user for the missing
decision or authority.

## Prepare the Reviewed Commit

After the first clean review, send the same worker one final preparation task:

- squash all commits unique to the task branch into one logical commit;
- use the subject required by `AGENTS.md`;
- delete the completed leaf task directory and remove its parent link in that
  squashed commit;
- when this is the final pool leaf, also remove any now-completed empty
  aggregate task ancestors and their queue link;
- leave the worktree and branch in place;
- do not cherry-pick to `main`;
- return the final commit SHA and confirm the worktree is clean.

Review the final diff again because task-tree deletion and any rebase or squash
preparation are part of the deliverable. Route new findings back to the worker
and have it amend the single task commit. After every amendment, the worker
must rerun and report the verification required by `AGENTS.md` for the changed
content before the primary agent re-reviews it. The reviewed SHA must name
exactly the commit presented at the user gate.

## Mandatory User Gate

Once the final commit is cleanly reviewed, stop the workflow. Do not transfer
it to `main`, remove its worktree or branch, or select the next task in the same
turn.

Report:

- pool identifier and current task path;
- reviewed commit SHA, branch, and worktree;
- verification results;
- that review has no remaining findings;
- any unrelated state on `main` that must be cleared before integration.

Ask the user either to authorize the workflow to integrate and continue, or to
transfer the commit themselves and confirm it. Treat broad original requests
such as "run the whole pool" as insufficient to bypass this per-task gate.

## Resume After User Intervention

On the next user turn, verify that the reviewed SHA and branch have not changed
and that the task worktree is still clean.

If the user authorizes integration, send the existing worker a follow-up task
to perform the mechanical completion workflow from `AGENTS.md`: cherry-pick the
reviewed commit to `main`, run the required post-commit tests on `main`, handle
task-caused failures under the same-message fix-commit rule, and remove the
worktree and branch only after the transfer is confirmed and `main` is clean.
This is an explicit exception to the task-worktree command location after the
gate: the worker may operate on the shared `main` worktree only after confirming
that it is clean, still points to the expected base, and has no conflicting
operation in progress. The primary agent still does not edit code or run tests.

If the user transferred the commit, inspect `main` to confirm the reviewed tree
is present, delegate any still-required post-commit verification to the worker,
and have it perform remaining safe cleanup. Do not infer successful integration
from the user's message alone.

If `main` has unrelated changes, the reviewed commit changed, cherry-pick would
conflict, tests fail for an unrelated reason, or cleanup cannot meet
`AGENTS.md`, stop and report the exact blocker. Never discard or absorb the
unrelated state.

Immediately before cleanup, re-check both the shared `main` worktree and the
task worktree. Remove neither the task worktree nor its branch unless the
transfer is confirmed, both worktrees are clean, and no Git operation is in
progress.

After integration, tests, worktree removal, and branch deletion are all
confirmed, automatically select the next ready leaf from the same pool and
start a fresh Terra/medium worker. Do not ask for another selection
confirmation. Apply the same review and user gate to every leaf until the pool
is exhausted.

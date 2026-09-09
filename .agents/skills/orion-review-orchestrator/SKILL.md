---
name: orion-review-orchestrator
description: >-
  Execute Orion task leaves through one implementation worker and review gates.
  Required by orion-task-runner for execution; also use for an explicit task
  subtree, task list, or tasks introduced by a commit.
---

# Orion Review Orchestrator

## Purpose

Keep the primary agent in coordinator/reviewer mode, with ownership of ordinary
implementation-plan documents on `main`. Give one leaf task at a time to a
fresh implementation worker, route every review finding back to that worker,
and stop at a user gate before transferring the reviewed commit to `main`.
After the user permits or confirms the transfer, finish integration and start
the next ready task automatically.

Never have two implementation workers active at once. The primary agent may
inspect, orchestrate, and update plans on `main`. Its only task-branch mutation
is the completion-metadata cleanup and commit amendment described below; it
must never edit implementation code, tests, or worker review fixes.

## Required Repository Guidance

Before selecting work, read and apply:

- `AGENTS.md`;
- `docs/reviews/RULES.md`;
- `../orion-minimal-implementation/SKILL.md` for worker implementation and concept review;
- `docs/plans/TASK.md` and the relevant descendant task nodes;
- `../orion-task-runner/SKILL.md` for task selection, ownership, claim, and
  task-tree rules; apply it in the coordinator role without recursive delegation.

Inspect `git status --short` and `git worktree list --porcelain`. Treat existing
changes, branches, worktrees, and claims as owned by somebody else unless this
workflow created them. Check candidate task files in every existing worktree so
a branch-local owner line still counts as a claim. Also inspect the candidate
path, moved-path equivalents, and task identity in relevant local branch refs,
including branches without an attached worktree. Any discovered owner line is
a claim; if ownership cannot be resolved safely, treat the candidate as
blocked. Do not stage, modify, or clean unrelated state.

A leaf deleted by an unintegrated task branch also remains occupied while its
completion awaits integration, even when the final squash removed its owner
line. Resolve that identity from the branch diff/history and pending worktree
before selecting another worker; do not interpret the deletion as a release.

## Resolve the Pool

Accept any of these pool definitions:

- a single numbered leaf file;
- a task-tree directory or parent `TASK.md`;
- an explicit list of numbered leaf files or composite task nodes;
- a commit that introduced task nodes.

Resolve every pool against the current canonical task tree defined by the
runner. Discover numbered leaf files and composite directories recursively,
sorting mixed siblings by their numeric prefixes at every depth. A composite's
`TASK.md` is context, never a separate implementation task. An explicit task
list limits membership; numeric filesystem order determines selection.

For a commit-defined pool, inspect added numbered leaf files and composite
`TASK.md` files with Git's rename detection so a moved task is not mistaken for
new work. Include newly introduced leaves and descendants of newly introduced
composites; do not include unrelated siblings under an existing parent. Map
these task identities to current paths through Git history and content, since
queue moves and local renumbering can change a path. If the commit uses paths
that no longer exist, trace their current identities instead of treating absent
files as completed tasks. Report ambiguity rather than broadening the pool.

Follow explicit dependencies and coordination gates before numeric selection
priority. Numeric order alone does not prohibit independent work when an earlier
leaf is blocked. Preserve any explicitly sequential pool constraint.

Select only an unclaimed, dependency-ready leaf. A claimed prerequisite is a
blocker; do not skip ahead to dependent work. If no ready leaf remains, either
report the pool complete or name the exact blocking task.

## Prepare the Plan on Main

Before launching a worker, decide whether the selected task needs a new or
updated ordinary implementation plan under `docs/plans/`. The primary
orchestrator owns that plan change; the implementation worker does not.

When a plan change is needed:

1. Confirm the shared `main` worktree is clean, has no Git operation in
   progress, and still contains no claim for the selected task. If the plan
   overlaps unrelated or user-owned changes, stop and report the conflict.
2. Edit only the relevant ordinary plan documents in the shared `main`
   worktree. Do not claim the task or edit its leaf file on `main`.
3. Commit the plan-only change directly on `main` with a concise one-line
   subject. Follow `AGENTS.md`; in particular, do not run tests for a
   documentation-only commit.
4. Re-check that `main` is clean, scan task ownership again, and record the new
   exact `main` HEAD as the worker base.

If no plan change is needed, use the current clean committed `main` HEAD.

After worker launch, a worker that finds a material plan gap must pause and
report it instead of editing the plan in its task worktree. The primary updates
and commits the plan on `main`, then tells the same worker to rebase its task
branch onto that exact new `main` commit without merging. The worker rechecks
the task worktree and resumes only after the rebase succeeds. Apply the same
rule to plan corrections discovered during review.

## Launch One Worker

Spawn a fresh worker for the selected leaf with:

- model `gpt-5.6-sol`;
- reasoning effort `high`;
- `fork_turns="none"`, or the smallest supported bounded fork, so the explicit
  model and effort override is applied rather than inherited;
- only the context needed for this task rather than the whole review thread,
  supplied explicitly in the worker prompt.

Tell the worker that it owns task claim, worktree setup, implementation, tests,
commits, review fixes, final squash, integration after approval, and subsequent
worktree/branch cleanup. Completion deletion from the queue and active plans
belongs exclusively to the primary coordinator. Treat ordinary implementation
plans as orchestrator-owned inputs and report required substantive plan changes
rather than editing them. The worker may update mechanical task-path references
in ordinary plans during an authorized queue move. It must retain the task leaf
and claim when preparing the squashed implementation commit.
Design, implementation instructions, substantive scope, and plan gaps remain
coordinator-owned. The primary agent owns selection, substantive plan maintenance
on `main`, review, and user communication.

The worker must:

1. Read `AGENTS.md`, the selected numbered leaf file, ancestor `TASK.md` files,
   referenced plans, and applicable local rules. Apply `orion-minimal-implementation` before
   and during implementation. Reading the runner here supplies worker claim
   and tracking rules; it does not authorize another worker or recursive
   orchestrator invocation.
2. Create a dedicated branch and worktree from the exact plan-updated committed
   `main` HEAD supplied by the primary without changing or including the shared
   working tree. Use a
   collision-free `codex/<task-slug>` branch and `.worktrees/<task-slug>` path,
   unless an existing workflow-owned branch and worktree are being resumed.
3. Inside that worktree, claim the selected task according to
   `orion-task-runner`. Make the isolated documentation-only claim commit before
   implementation and include no substantive plan changes or unrelated files. When
   starting upcoming work, perform the required task-tree move as part of that
   isolated start/claim change. Do not put this pre-review claim commit directly
   on `main`; it will be included in the branch's final squash.
4. Until the user gate, perform every subsequent command and edit in that
   worktree. Preserve all unrelated shared-workspace state.
5. Apply `orion-minimal-implementation` before and during implementation, including
   its behavioral slices, concept checks, and final self-review. Implement
   production behavior and tests under `AGENTS.md`, run focused checks while
   developing, and run the required development verification.
6. Commit its work and return the task path, worktree, branch, base and head
   SHAs, the required `orion-minimal-implementation` summary of the problem, solution,
   changed parts and their specific changes, verification commands and results,
   and any known risks. It must not transfer the task commit to `main` yet.

If an isolated claim cannot be made safely, the worker must stop without
claiming or editing implementation files and report why.

## Review Loop

Wait for the worker, then review the complete branch diff against its real
base. Apply `docs/reviews/RULES.md`, relevant `@AiRule` class comments, the task
contract, repository conventions, and `orion-minimal-implementation` in read-only
review mode. Check implementation and tests, not only the worker summary.

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

After the first clean review, ask the same worker to squash all task-unique
commits into one logical commit with the subject required by `AGENTS.md`.
The worker retains the numbered task leaf and its claim, leaves the worktree
and branch in place, and returns the prepared SHA with a clean worktree. It
must not cherry-pick to `main` or perform completion deletion.

The primary coordinator then performs completion-only metadata cleanup in
that dedicated task worktree, after confirming the reported SHA, clean state,
and that the worker is idle:

1. Delete the completed numbered leaf file.
2. Walk upward and remove completed empty composite directories in full,
   including their `TASK.md`, only when aggregate acceptance and remaining scope
   are satisfied. Preserve parents with unfinished siblings, the two queue
   roots, and root `docs/plans/TASK.md`.
3. Remove the task's outstanding-work entries from active plans and replace
   still-needed dependency links with verified completion evidence. Do not
   maintain parent child lists or renumber remaining entries to close gaps.
4. Check the metadata diff and affected references, stage only this cleanup,
   and amend the same commit without changing its subject. Do not change
   implementation code, tests, substantive design, or implementation instructions.

This is the explicit exception to the coordinator's task-branch mutation rule.
The coordinator checks documentation-only cleanup without rerunning Maven and
reviews the complete final diff, including deletion and squash preparation.
Send new implementation findings to the same worker for fixes, amendment, and
the verification required by `AGENTS.md`; the worker must preserve coordinator
cleanup. The coordinator owns any correction to completion metadata and
re-reviews after every amendment. Present only the resulting clean, reviewed
SHA at the user gate; it must include the coordinator's completion cleanup.

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
start a fresh Sol/high worker. Do not ask for another selection
confirmation. Apply the same review and user gate to every leaf until the pool
is exhausted.

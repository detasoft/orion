---
name: orion-change-orchestrator
description: >-
  Use when an Orion source, build, or configuration change requires an
  implementation worker and review gates, whether queue-backed or direct.
  Exclude documentation-only and repository workflow-control changes.
---

# Orion Change Orchestrator

## Scope and Roles

Use this workflow for requested source, build, and configuration changes,
including a direct request with no queued task. Files in the repository
[workflow-control scope](../../../docs/definitions.md#workflow-control-scope)
follow its shared rules and stay outside the implementation worker/worktree,
except for queued task-tree state owned by this orchestrator as specified below.
When a request mixes implementation with workflow-control or other documentation
changes, separate the scopes and use this workflow only for the implementation
portion. Creating or editing tasks and their queue relationships belongs directly to
[orion-task-runner](../orion-task-runner/SKILL.md), without launching an
implementation worker. During queued execution, this orchestrator applies the
runner itself for claims, queue moves, pause state, and completion metadata,
committing every task-tree transition directly on `main`. Review, audit, and
status requests remain read-only unless the user asks to implement changes.

Keep the primary agent in coordinator/reviewer mode. Give one bounded change
at a time to a fresh implementation worker, route implementation findings back
to that worker, and stop at the user gate before integration. Never have two
implementation workers active at once.

The primary agent running this orchestrator owns selection, governing
implementation plans, review, user communication, and every queued task-tree
edit. It commits task-tree state directly on `main` and never changes it through
a task branch or worktree. It never edits implementation code, tests, or worker
review fixes. The worker owns worktree setup, implementation, tests, commits,
review fixes, final squash, and integration and worktree/branch cleanup after
approval.

## Resolve the Change

Read `AGENTS.md`, `docs/reviews/RULES.md`, and
[orion-minimal-implementation](../orion-minimal-implementation/SKILL.md). Inspect
`git status --short` and `git worktree list --porcelain`. Preserve existing
changes, branches, and worktrees belonging to others. Never stage, discard,
or absorb unrelated state.

Resolve the user's request into one of these scopes:

- **Direct change:** the user request defines the bounded result and affected
  area. No queue node, leaf path, claim, task-tagged subject, or task deletion
  is required. Do not create a task merely to run this workflow. If the user
  supplies several direct changes, preserve their explicit order and dependencies.
- **Queued work:** a numbered leaf file, composite directory or `TASK.md`,
  explicit task pool, or commit that introduced tasks. Apply the runner's task
  model, numeric traversal, ownership, and dependencies in the coordinator role;
  do not invoke this orchestrator recursively. Queue-backed selection uses
  numeric filesystem order within the selected pool.

For queued work, check the candidate leaf and ancestors in every relevant
worktree and local branch, including unattached branches. Re-resolve moved or
renumbered identities through Git history and content. An owner line anywhere
counts as a claim. A leaf deleted by an unintegrated task branch remains occupied
while completion awaits integration, even after squash removed its owner line.
Use the branch diff/history and pending worktree to establish that identity.
Do not infer release or completion from a missing file or an old timestamp.

For a commit-defined task pool, inspect added numbered leaves and composite
`TASK.md` files using rename detection. Include newly introduced leaves and
descendants of newly introduced composites, not unrelated siblings of existing
parents. Resolve their current identities; report ambiguity rather than
broadening the pool. Composites are context, never implementation leaves.

Select only an unclaimed, dependency-ready queued leaf. Honor explicit gates
and sequential constraints. Numeric priority alone does not prohibit genuinely
independent work when an earlier leaf is blocked; never skip a prerequisite to
execute its dependent. If nothing is ready, report the exact blocker or that
the pool is exhausted. Direct changes require only their actual prerequisites,
not a fabricated leaf or queue dependency.

## Prepare Governing Plans and Base

Decide whether the change needs a new or updated governing implementation plan.
The primary agent creates or corrects that documentation directly on `main`,
outside this workflow and commits it separately with a one-line subject. Do not
delegate that edit to the worker. Check for overlapping user-owned edits, staged
unrelated files, or a Git operation first; report conflicts without changing
them. Documentation-only plan commits do not require tests.

After governing plans are current, recheck queued ownership. For queued work,
apply `orion-task-runner` directly on `main`: make any required queue move, add
the claim, update only mechanically required task-path references, and
immediately commit that state as one atomic documentation-only commit. Do not
delegate this edit or commit: the primary orchestrator performs it directly on
`main`, never through a worker, subagent, task branch, or worktree. If the claim
cannot be isolated from unrelated changes, report the conflict without launching
the worker. Record the resulting exact committed `main` HEAD as the worker base.
Direct changes skip the claim and use the current committed `main` HEAD. Existing
unstaged changes must remain outside the worker's isolated base; resolve any
overlap with the requested work before proceeding.

Files in the workflow-control scope are never worker edit targets. Standalone
task-description, queue, and dependency edits belong directly to the runner;
other documentation belongs directly to the primary agent on `main`.

If the worker discovers a material gap in its governing plan, it reports it
instead of revising that input. The primary agent pauses this workflow, updates
and commits the plan directly on `main`, then asks the same worker to rebase onto
that exact commit, recheck the worktree, and resume. Apply the same rule to
substantive plan corrections found during review.

## Launch One Worker

Spawn a fresh worker with the same model as the primary agent, reasoning effort
`high`, and `fork_turns="none"` or the smallest supported bounded fork. Supply
only the explicit context needed for the selected change.

The worker must:

1. Read `AGENTS.md`, the bounded request, governing plans, and applicable rules.
   For queued work, also read the leaf and ancestor `TASK.md` files. Apply
   `orion-minimal-implementation` before and during implementation, including
   behavioral slices, concept checks, and final self-review. Reading either
   routing skill in this role never launches another implementation worker.
2. Create a collision-free `codex/<change-slug>` branch and
   `.worktrees/<change-slug>` from the exact committed base supplied by the
   coordinator. Resume an existing branch/worktree only if this workflow owns it.
3. For queued work, verify that the supplied base already contains the selected
   leaf's claim and any required queue move. Never edit task-tree files or include
   task-tree changes in worker commits, squashes, or amendments. Direct changes
   have no task-tree state.
4. Until the integration gate, perform implementation commands and edits only
   in that worktree. Preserve unrelated shared-workspace state. Treat governing
   plans, other documentation, and the workflow-control scope as primary-owned
   inputs, never worker edit targets.
5. Implement production behavior and tests under `AGENTS.md`, run focused
   checks and the required development verification.
6. Commit the change and return its scope, any actual task path, worktree, branch,
   base and head SHAs, and the required `orion-minimal-implementation` summary:
   problem, solution, changed parts and specific changes, verification results,
   risks, and remaining work. Do not transfer to `main` yet.

## Review Loop

Review the complete branch diff against its real base. Apply
`docs/reviews/RULES.md`, relevant `@AiRule` comments, the requested behavior,
repository conventions, and `orion-minimal-implementation` in read-only review mode.
Check the actual implementation and verification, not only the worker summary.

The worker owns implementation verification. Do not run Maven solely for review;
send missing, inadequate, or failed checks back as findings. For each actionable
finding, provide evidence and the required behavior, send it to the same worker,
wait for fixes and verification, and review the complete resulting diff again.
Never silently repair implementation from the coordinator thread. If progress
requires a new user decision or authority, report the specific blocker.

## Prepare the Reviewed Commit

After clean implementation review, ask the worker to squash all change-unique
commits into one logical commit and leave its worktree clean:

- For queued work, use the task-tagged subject required by `AGENTS.md`. The task
  leaf and claim already exist on `main` and must not appear in the branch's
  change-unique commits.
- For a direct change, use a descriptive single-line subject without a fabricated
  task path or tag. No task-metadata cleanup applies.

The worker returns the prepared SHA and leaves the branch/worktree in place.
Task-tree completion does not happen before the integration gate and is never
amended into this implementation commit.

Review the complete final diff after preparation. Send new implementation
findings to the same worker for fixes, amendment, and the verification required
by `AGENTS.md`. Re-review after every amendment and present only the final clean,
reviewed implementation SHA at the gate.

## Mandatory User Gate

Once the final commit is reviewed without remaining findings, stop. Do not
transfer it, remove the worktree/branch, or select the next change in the same turn.

Report the direct scope or task pool and actual leaf path, final reviewed SHA,
branch/worktree, verification results, clean review outcome, and any unrelated
state that affects integration. Ask the user to authorize integration and
continuation, or to transfer the commit themselves and confirm it. Broad requests
such as "run all changes" or "run the whole pool" do not bypass this per-change gate.

## Resume and Finish

On the next user turn, verify the reviewed SHA and branch are unchanged and the
dedicated worktree is clean. If integration is authorized, send the same worker
the mechanical integration task under `AGENTS.md`: cherry-pick the reviewed
commit to `main`, run required post-commit verification, handle change-caused
failures with the same-subject fix-commit rule, and remove only the completed
worktree and branch once transfer and the required clean-state checks succeed.

This permits the worker to operate on shared `main` after the gate, after
checking its expected base, staged/working state, and absence of a conflicting
Git operation. Never discard or absorb unrelated changes. If integration would
conflict, the reviewed commit changed, unrelated test failures occur, or cleanup
cannot meet `AGENTS.md`, report the exact blocker.

If the user transferred the commit, verify the reviewed delta is present on
`main`, delegate any required implementation verification and worktree cleanup
to the worker, and confirm the outcome rather than relying on the message alone.
The primary agent does not edit implementation code or run its tests.

After queued implementation is present and verified on `main`, this orchestrator
immediately applies `orion-task-runner` there and creates one separate atomic
documentation-only completion commit. Delete the completed leaf and eligible
empty composite ancestors, update outstanding-work and dependency references,
and preserve unfinished siblings and queue roots. Never delegate this task-tree
edit, perform it in the worktree, or amend it into the implementation commit. Do
not run Maven for the documentation-only completion commit. If integration or
verification failed, leave the claimed task intact and report the blocker.

Confirm transfer, verification, worktree removal, branch deletion, and the
task-tree completion commit before reporting queued work complete. For a
continuing pool or explicit change list, select the next ready item automatically
in its applicable order and launch a fresh Sol/high worker. Apply the same review
and user gate to each item; a single direct change ends when its integration and
cleanup are complete.

## Completed Task Boundary

After all completion conditions for one bounded change are confirmed, emit a
self-contained user-facing completion report before starting later work. State
what was solved, how it was solved, which parts changed and what changed in each,
actual verification results, remaining risks or work, and concrete next steps
that can be taken.

The completed task's worker is then retired. Do not send it messages or
follow-up tasks and never reuse it for another direct change or queued leaf.
Reuse of the same worker for review fixes, commit preparation, and integration
is required only while its original bounded change remains active.

For a continuing pool or explicit change list, reconstruct the next item's
working context from current durable evidence: `HEAD`, workspace and worktree
state, active task-tree nodes, applicable plans and rules, relevant current
review reports, unresolved user decisions, and the still-authorized scope. Do
not carry forward the completed task's investigation chronology, rejected
alternatives, transient reasoning, or worker conversation. Retain a completed
task fact only when current repository evidence makes it a dependency or
invariant of the next item. Use native context compaction when available; when
it is not available, this reconstructed working set is the context reset.

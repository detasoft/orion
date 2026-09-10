---
name: orion-change-workflow
description: >-
  Use when an Orion change is large, uncertain, cross-boundary, review-heavy,
  or otherwise benefits from a task-backed isolated implementation worker,
  including but not limited to queued task execution.
---

# Orion Change Workflow

Execute one task-backed implementation through an isolated worker, primary-agent
review, and an explicit user integration gate.

## Selection and required mechanics

Read `AGENTS.md`, `docs/reviews/RULES.md`,
[the canonical change workflow](../../../docs/definitions.md#change-workflow),
[orion-task-runner](../orion-task-runner/SKILL.md), and
[orion-minimal-implementation](../orion-minimal-implementation/SKILL.md). Use
[orion-quick-workflow](../orion-quick-workflow/SKILL.md) for every
primary-owned documentation milestone.

Use the workflow definitions as selection guidance. Task execution recommends
this workflow but does not require it; the executor may choose simple or
quick execution when their mechanics are proportionate. Conversely, a large
documentation, skill, Makefile, or other change may use this workflow. Do not
select by file type or queue membership alone.

Once this workflow is selected, every execution has one executable task-tree
leaf. There is no taskless direct-execution mode. Before launching a worker, the
primary agent must create a suitable claimed leaf or claim a matching existing
leaf and commit that state on `main`. A user prompt, deadline, clear plan, or
small diff does not replace this committed task state.

## Roles and invariants

The primary agent coordinates, owns governing inputs and task-tree execution
state, reviews the implementation, communicates with the user, and applies that
state through `orion-task-runner`. It never edits worker deliverables or review
fixes and does not run Maven solely for review.

One fresh implementation worker owns the task branch and worktree, explicit
task deliverables, proportionate verification, review fixes, commit preparation,
integration after approval, and branch/worktree cleanup. Deliverables may be in
any file category, including documentation or skills. Never run two
implementation workers at once. The worker never edits the claim, pause, or
completion state governing its own execution or primary-owned governing inputs.

Preserve unrelated changes, branches, worktrees, claims, and Git operations.
Never stage, discard, absorb, or overwrite state belonging to another user,
task, or session.

## Quick reference

| State | Required action |
| --- | --- |
| No matching leaf exists | Create a minimal claimed leaf and commit it on `main`. |
| Matching unclaimed leaf exists | Claim it and commit the state on `main`. |
| Matching leaf is claimed | Stop or select independent ready work; never take it over. |
| Worker result has findings | Return them to the same worker and review again. |
| Final commit is clean | Stop and request user authorization to integrate. |
| Integration is verified | Remove worktree/branch, then commit task completion. |

## Resolve and claim one task

Inspect `git status --short`, `git worktree list --porcelain`, current branches,
the task tree, relevant plans, and existing claims. Resolve moved or renamed
task identities through repository evidence. An owner line in any relevant
worktree or branch is a claim; a deleted leaf in an unintegrated task branch
remains occupied. Do not infer completion from a missing file or old timestamp.

For queued work, select one unclaimed, dependency-ready numbered leaf using
`orion-task-runner`. Honor the user's pool, numeric order, dependencies, and
gates. Composites provide context and are not executable tasks.

For a direct implementation request, search for a matching task before creating
one. A matching claim blocks duplicate execution. If an unclaimed matching leaf
exists, use it. Otherwise create the smallest coherent executable leaf needed
for the requested outcome; do not create speculative siblings or a composite
when one leaf is sufficient.

Choose collision-free `codex/<change-slug>` and `.worktrees/<change-slug>` names.
Record their exact names, session identity, owner, and start time in the claim as
specified by `orion-task-runner`. Use `orion-quick-workflow` to commit one
coherent pre-worker documentation state on `main`:

- a new direct-request leaf is created already claimed; or
- an existing leaf is moved when required and claimed.

Include a required governing plan in that documentation state when it is part
of the same coherent worker input; otherwise commit it at the earlier moment
when it becomes accurate. Do not create a plan merely to route understood work.
The exact committed `main` HEAD containing the claim and all governing inputs is
the worker base. Never launch from uncommitted task state.

## Launch one worker

Spawn a fresh worker with the same model as the primary agent, reasoning effort
`high`, and `fork_turns="none"` or the smallest supported bounded fork. Supply
only the selected task, its ancestors and plans, the exact base SHA, branch and
worktree names, applicable rules, and expected return contract.

The worker must:

1. Read `AGENTS.md`, the leaf and ancestor `TASK.md` files, governing plans,
   `docs/reviews/RULES.md`, relevant `@AiRule` comments, and
   `orion-minimal-implementation`.
2. Create and use the claimed branch and worktree from the exact committed base.
   Verify that the base contains the claim and that the worktree is clean.
3. Treat the current task's claim/completion state and primary-owned governing
   inputs as read-only. Own every explicit task deliverable, including ordinary
   documentation or workflow-control files when the task names them as outputs.
4. Implement the smallest complete task. Add tests when functionality changes
   and run checks and development verification required by `AGENTS.md` for the
   actual deliverables.
5. Commit the implementation and return the base/head SHAs, task path,
   branch/worktree, changed parts, verification evidence, risks, remaining work,
   and the complete `orion-minimal-implementation` summary. Do not integrate.

If the worker discovers a material governing-plan gap, it stops and reports it.
The primary agent updates the plan on `main` through `orion-quick-workflow`,
then has the same worker rebase onto that exact commit, verify its worktree, and
resume. Apply the same rule to substantive plan corrections found during review.

## Review loop

Review the complete branch diff against its real base. Apply the task, governing
plans, `docs/reviews/RULES.md`, repository conventions, `@AiRule` comments, and
`orion-minimal-implementation` in read-only review mode. Verify actual code,
tests, consumers, and recorded command results rather than trusting the summary.

Send every actionable finding with evidence and required behavior to the same
worker. The worker fixes it, runs affected verification, and returns the result.
Review the complete diff again. The primary agent does not silently repair code
or run missing implementation tests. Stop for a material user decision,
authority gap, unsafe repository state, or genuine implementation blocker.

After a clean review, have the worker squash every task-unique commit into one
logical commit using the `AGENTS.md` subject:

```text
<imperative summary> [task: <leaf path relative to its queue root>]
```

The worker leaves its worktree clean and returns the prepared SHA. Review the
complete final diff again. Return any new finding to the same worker for an
amendment and required verification, then re-review.

## Mandatory user integration gate

When the final prepared commit has no remaining findings, stop. Report the task
path, final SHA, branch/worktree, verification results, clean review outcome,
risks, and unrelated state. Ask the user to authorize integration or to transfer
the commit and confirm it.

Do not cherry-pick, amend, remove the worktree or branch, complete the task, or
start another implementation in the same turn. A request to run a whole pool
does not bypass the per-task gate.

## Resume, integrate, and complete

On the next user turn, verify that the reviewed SHA and branch are unchanged and
the worktree is clean. After authorization, send the same worker the mechanical
integration work required by `AGENTS.md`: cherry-pick the reviewed commit to
`main`, run post-commit verification, apply the same-subject fix-commit rule for
change-caused failures, and remove only the completed worktree and branch after
successful transfer and clean-state checks.

If the user transferred the commit, verify the reviewed delta on `main` and use
the worker for any still-required implementation verification and owned cleanup.
Report conflicts, changed SHAs, unrelated failures, or unsafe cleanup instead of
discarding state.

After the implementation is present and verified on `main` and its worktree and
branch are gone, apply `orion-task-runner` and `orion-quick-workflow` to the
completion state. Delete the completed leaf and eligible empty ancestors, update
required references and outstanding-work entries, preserve unfinished siblings
and queue roots, and commit one coherent documentation state. The
quick-workflow executor decides whether any check, including Maven, is useful
and proportionate for that state commit.

When invoked from `orion-review`, the review owner then revalidates and commits
the corresponding `MODULE_REVIEW.md` update through `orion-quick-workflow`.
Do not report the task complete until transfer, verification, worktree removal,
branch deletion, and task completion are all confirmed.

If a worker pauses incomplete work, it reports the next step without editing
task state. The primary agent records the pause in the existing claim and
commits it through the quick workflow.

## Completed-task boundary

Give the user the required `orion-minimal-implementation` summary: what was
solved, how, which parts changed and how, verification results, risks, remaining
work, and next steps. Retire the worker after its one task; never reuse it for a
later leaf.

For a continuing pool, reconstruct context for the next ready leaf from current
`HEAD`, workspace/worktree state, task tree, plans, current review reports, and
unresolved decisions. Do not carry the completed worker conversation or rejected
alternatives forward. Start the next task with a fresh worker and the same gate.

## Red flags and common mistakes

- Launching a worker from a user prompt without committed create-or-claim state.
- Preserving the old taskless direct-change mode under another name.
- Selecting this workflow solely because a task exists or a file has a certain type.
- Letting the worker edit execution state or primary-owned inputs it was not assigned.
- The primary agent fixing implementation findings or rerunning Maven for review.
- Integrating, cleaning up, or selecting the next task before user authorization.
- Deleting the task before verified integration and worktree/branch cleanup.

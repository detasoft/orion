# Orion repository workflows

Orion uses three execution workflows whose mechanics can apply to any file.

Workflow names describe defaults rather than file eligibility. Module-review
repairs normally start with the simple workflow, task execution normally starts
with the change workflow, and documentation, task-tree and skill changes normally
start with the quick workflow. The executor may choose another workflow from
the actual magnitude, uncertainty, risk and useful review or isolation. Any
workflow may change any file when its mechanics fit the work.

## Simple workflow

Use the simple workflow for a bounded, predictable change whose implementation
and review can safely happen in the current worktree. The primary agent chooses
coherent checkpoints as the work proceeds. At each checkpoint it makes the
change, verifies and self-reviews it, stages only the relevant files, and asks
the user to review the staged result. It commits only after explicit user
approval and does so immediately before starting another checkpoint. All
checkpoint subjects use `<task name>: <imperative checkpoint
summary>` with one stable task-name prefix so they can be found and squashed
together later. It does not create or claim a task merely for routing, write a
routing plan, launch a worker, or create a branch or worktree. When executing an
existing leaf, task-runner may own claim and completion state separately.

Ordinary documentation changed as part of the implementation stays with that
implementation and is included in the staged commit. Independent workflow-control
state remains governed by its own ownership and commit rules.

## Change workflow

Use the change workflow when work is not local and predictable, changes public
or persisted contracts, crosses module or ownership boundaries, materially
affects lifecycle or concurrency, or otherwise benefits from an isolated worker
and coordinator review. Task execution recommends this workflow but does not
force it.

Before a worker starts, the primary agent either creates a task leaf or claims a
matching existing leaf and commits that task-tree state on `main`. The worker
implements in a dedicated branch and worktree. The primary agent reviews the
result, returns findings to the worker, and presents the final reviewed commit
to the user before integration. After approval, the implementation is
integrated and verified, the worktree and branch are removed, and task-tree
completion is committed separately.

Governing inputs and task execution state remain primary-owned and are committed
on `main` when their statement becomes accurate. This includes task
creation or claim before worker launch, material plan corrections while work is
in progress, task completion after verified integration, and module-review
updates after a finding is confirmed or resolved. The worker owns every explicit
task deliverable, including documentation and skills, unless repository rules
assign that particular state to the primary agent.

## Quick workflow

Use the quick workflow by default for ordinary docs, repository rules,
skills, task-tree state and module review reports. It may change any file,
including a Makefile, when a prompt direct edit is proportionate. Commit
directly on `main` without creating a routing task, worker, branch, worktree,
checkpoint gate or test coverage. Verification is optional and chosen by the
executor; existing Maven or other project checks are allowed when useful, but
none is required merely by the workflow and validation is not invented when
nothing meaningful applies.

The quick workflow also executes primary-owned state commits at the
moments defined by a surrounding simple or change workflow. It does not add a
new user interaction or split one coherent documentation state by file category.
It does not fold lifecycle documentation into an implementation commit.

## Skill structure

- `orion-simple-workflow` owns direct current-worktree implementation, logical
  checkpoint selection, and each checkpoint's staged user-review gate.
- `orion-change-workflow` owns task-backed worker execution, coordinator review,
  the integration gate, and cleanup.
- `orion-quick-workflow` owns prompt direct edits and primary-owned state commits
  reached during other workflows.
- `orion-task-runner` owns only task-tree modeling, selection, and state edits;
  it recommends but does not prescribe `orion-change-workflow` for execution.
- `orion-review` owns audits and module reports. For each authorized repair it
  considers the simple workflow first and leaves the final choice to the
  executor's assessment of the actual repair.

The canonical definitions in `docs/definitions.md` provide shared selection and
documentation-commit rules so these skills do not redefine the same boundaries
independently.

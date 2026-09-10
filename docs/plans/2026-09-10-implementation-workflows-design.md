# Orion implementation workflows

Orion uses two implementation workflows selected before source, build, or
configuration files are changed.

## Simple workflow

Use the simple workflow for a bounded, predictable change whose implementation
and review can safely happen in the current worktree. The primary agent chooses
coherent checkpoints as the work proceeds. At each checkpoint it makes the
change, verifies and self-reviews it, stages only the relevant files, and asks
the user to review the staged result. It commits only after explicit user
approval. All checkpoint subjects use `<task name>: <imperative checkpoint
summary>` with one stable task-name prefix so they can be found and squashed
together later. It does not create or claim a task, write an implementation
plan merely for routing, launch a worker, or create a branch or worktree.

Ordinary documentation changed as part of the implementation stays with that
implementation and is included in the staged commit. Independent workflow-control
state remains governed by its own ownership and commit rules.

## Change workflow

Use the change workflow when the implementation is not local and predictable,
or when it changes public or persisted contracts, crosses module or ownership
boundaries, materially affects lifecycle or concurrency, or otherwise benefits
from an isolated worker and coordinator review.

Before a worker starts, the primary agent either creates a task leaf or claims a
matching existing leaf and commits that task-tree state on `main`. The worker
implements in a dedicated branch and worktree. The primary agent reviews the
result, returns findings to the worker, and presents the final reviewed commit
to the user before integration. After approval, the implementation is
integrated and verified, the worktree and branch are removed, and task-tree
completion is committed separately.

Documentation that governs or records the workflow remains primary-owned and is
committed on `main` when its statement becomes accurate. This includes task
creation or claim before worker launch, material plan corrections while work is
in progress, task completion after verified integration, and module-review
updates after a finding is confirmed or resolved. Ordinary documentation that
describes the implemented product behavior stays with the worker implementation
commit unless repository rules explicitly assign it to the primary agent.

## Skill structure

- `orion-simple-workflow` owns direct current-worktree implementation, logical
  checkpoint selection, and each checkpoint's staged user-review gate.
- `orion-change-workflow` owns task-backed worker execution, coordinator review,
  the integration gate, and cleanup.
- `orion-task-runner` owns only task-tree modeling, selection, and state edits;
  execution routes to `orion-change-workflow`.
- `orion-review` owns audits and module reports. For each authorized repair it
  prefers the simple workflow and uses the change workflow only when the repair
  does not meet the simple workflow boundary.
- `orion-change-orchestrator` is removed after its guarantees and consumers have
  moved to the two workflow skills.

The canonical definitions in `docs/definitions.md` provide shared selection
criteria and documentation-commit rules so these skills do not redefine the
same boundary independently.

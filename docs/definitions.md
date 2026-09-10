# Repository definitions

## Repository workflows

An **implementation change** modifies source, tests, build files, configuration,
or ordinary product documentation that accompanies such a modification. Select
the simple or change workflow before editing implementation files. A change
that affects only documentation uses the document workflow.

When the simple or change workflow reaches a primary-owned documentation
milestone, use the document workflow for that commit without replacing or
changing the surrounding implementation workflow.

### Simple workflow

The **simple workflow** is the preferred path for a bounded, predictable change
that can be implemented and reviewed safely in the current worktree. Use it
when the required result, affected files and existing production path are clear,
and the change does not need isolated worker ownership or task tracking.

Typical examples include:

- a clear correction to an existing Makefile or `pom.xml`;
- a small bug fix in an understood production path;
- a predictable refactoring whose consumers and mechanical result are known;
- a local deletion or simplification with verified consumers.

An example does not qualify automatically. Use the change workflow when the
work materially changes a public, wire or persisted contract; crosses uncertain
module, ownership, lifecycle or concurrency boundaries; adds a dependency,
service or durable state; has unresolved design choices; or cannot be reviewed
confidently as a sequence of staged current-worktree checkpoints. Within
`orion-review`, prefer the simple workflow for every repair that satisfies this
boundary.

The primary agent performs the simple workflow directly in the current
worktree and branch:

1. Inspect the repository state and preserve unrelated changes.
2. Derive one concise, stable task name from the user request. This name is a
   commit prefix, not a task-tree node or claim.
3. Identify logical implementation checkpoints as the work proceeds. Each
   checkpoint must be an independently coherent, reviewable change that leaves
   the repository working; do not fragment by file or hide a real checkpoint
   merely to reduce the number of user reviews.
4. Implement and verify one checkpoint without creating or claiming a task,
   writing a plan merely for routing, launching a worker, or creating a branch
   or worktree. Include ordinary product documentation for that checkpoint.
5. Stage only files and hunks owned by the checkpoint. Self-review the staged
   result, show it and the verification outcome to the user, and ask for review.
   Do not commit yet.
6. If the user requests corrections, update, verify, stage and self-review the
   checkpoint again, then repeat the user gate.
7. After explicit approval, verify that the index is the reviewed result and
   commit it using `<task name>: <imperative checkpoint summary>`. Keep the task
   name byte-for-byte identical in every checkpoint subject so the commits can
   be found and squashed together later.
8. Run the post-commit verification required by `AGENTS.md`, then continue with
   the next logical checkpoint through the same stage, review and approval loop.

A task with one logical checkpoint produces one commit. Do not rewrite or
squash approved checkpoint commits unless the user requests it. If post-commit
verification requires a fix commit, follow the same-subject rule in `AGENTS.md`.

The simple workflow creates no routing documentation. A surrounding workflow
may still own documentation whose truth changes before or after the simple
implementation, such as a `MODULE_REVIEW.md` finding. Commit such documentation
separately on `main` at the moment required by its ownership rules; it is not
part of the staged implementation gate.

### Change workflow

The **change workflow** is the task-backed path for every implementation that
does not satisfy the simple workflow boundary. It is also mandatory when the
user asks to execute a task-tree leaf or another queued task. Every change
workflow has one executable task: before launching a worker, the primary agent
either creates a suitable leaf or claims a matching existing leaf and commits
that task-tree state on `main`.

The primary agent coordinates and reviews; one fresh implementation worker owns
the dedicated branch and worktree, implementation, tests, review fixes and
prepared implementation commit. The primary agent returns findings to that
worker and presents the final reviewed commit to the user before integration.
Only explicit user approval permits integration. After approval, integrate and
verify the implementation on `main`, remove the completed worktree and branch,
then commit task-tree completion separately.

Documentation commits occur at the lifecycle point where their statements
become accurate; they are not postponed into the implementation commit:

- create or claim task-tree state before worker launch;
- commit required governing plans before they become worker inputs;
- commit material plan corrections on `main` before the worker resumes from
  the corrected base;
- commit task-tree completion only after verified integration and cleanup;
- commit `MODULE_REVIEW.md` updates when a finding is confirmed and again after
  its repair is verified on `main`.

The primary agent owns those governing and workflow-control documents on
`main`. The worker owns ordinary product documentation that describes its
implemented behavior and includes it in the implementation commit unless a
repository rule explicitly assigns that document to the primary agent.

### Document workflow

The **document workflow** is the direct path for changes that affect only
documentation, including ordinary documents, repository rules, skills, task-tree
files and `MODULE_REVIEW.md` reports. It also performs primary-owned
documentation commits at the lifecycle moments established by a surrounding
simple or change workflow.

Work directly on `main`. Do not create, claim, move or delete a task merely to
route a documentation change; do not write an implementation plan for routing,
launch a worker, create a branch or worktree, or add a staged user-review gate.
When the requested documentation change is itself a task-tree operation, apply
`orion-task-runner` to that operation, but create or remove task nodes only when
that is the requested state change or a lifecycle action required by the
surrounding change workflow.

Make the requested edits and commit one coherent documentation state change
promptly with a descriptive single-line subject. Do not split one logical
documentation change merely because it touches both workflow-control and
ordinary documentation. Preserve unrelated changes and keep genuinely unrelated
documentation changes in separate commits.

Do not add tests, test coverage or implementation scaffolding for a document
workflow, and do not run Maven or project tests before or after its commit. Use
only a quick check that is already available and meaningful for the edited
artifact, such as `git diff --check`, the repository skill validator for a
changed skill, a task-tree structural check for changed task state, or a link
check for edited links. Do not invent a check or broaden verification merely to
claim that the document was tested. If no useful check exists, inspect the diff,
commit it, and report that no automated check was applicable.

Within a simple or change workflow, the surrounding workflow determines when a
documentation state is accurate and who owns it. The document workflow performs
that edit, quick check and commit without adding an interaction, approval gate,
worker handoff or extra commit split. It never folds a primary-owned lifecycle
document into an implementation commit.

## Workflow-control scope

The **workflow-control scope** is the exact set of repository files that define
or record agent workflow rather than product implementation:

- repository-local skills under `.agents/skills/`;
- the filesystem task tree rooted at `docs/plans/TASK.md`, including
  `current-work/`, `upcoming-work/`, their descendants, and the root `TASKS.md`
  compatibility pointer when it changes as part of task-tree maintenance;
- every module review report named `MODULE_REVIEW.md`.

The scope excludes `AGENTS.md`, ordinary plan documents outside the task tree,
production code, tests, build files, and configuration unless another rule names
them explicitly. Mechanically required references from an in-scope change do not
make their containing files part of the scope.

For repository routing and Maven policy, changes within this scope are
workflow-control changes rather than product implementation and use the document
workflow. Make and commit them directly on `main`, never in an implementation
branch or worktree and never through an implementation worker. During queued
execution, the primary agent running the change workflow owns task-tree state
transitions; otherwise the primary agent owns the in-scope change. Do not run
Maven for an in-scope-only commit. Use only quick validation that is meaningful
for the affected resource. Coherent documentation changes may share one commit
and do not require isolation merely because some files are inside this scope
and others are ordinary documentation.

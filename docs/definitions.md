# Repository definitions

## Repository workflows

Workflow selection controls how a change is executed, not which files it may
touch. Any workflow may change source, tests, build files, configuration,
documentation, skills, or task files when its mechanics are appropriate.

Use these defaults as recommendations, not routing rules:

| Work being considered | Default workflow |
| --- | --- |
| A repair found during module review | Simple workflow |
| Execution of a task-tree leaf | Change workflow |
| Documentation, task-tree, or skill editing | Quick workflow |

The executor makes the final choice from the actual size, uncertainty, risk,
need for isolation, useful verification, and desired user interaction. A task
may use the simple or quick workflow; a large documentation or skill change may
use the simple or change workflow; a Makefile change may use the quick workflow.
File type, queue membership, and the stage that discovered work never force a
workflow by themselves. Once selected, the workflow's own mechanics and gates
are binding.

When the simple or change workflow reaches a primary-owned documentation
milestone, use the quick workflow for that commit without replacing or
changing the surrounding implementation workflow.

### Simple workflow

The **simple workflow** is the preferred path for a bounded, predictable change
that can be implemented and reviewed safely in the current location. Use it
when the required result, affected files and existing production path are
clear, the expected change is non-destructive, and the risk of blocking other
work is low. These are indicators that isolated worker ownership and task
tracking may not add enough value.

Typical examples include:

- a clear correction to an existing Makefile or `pom.xml`;
- a small bug fix in an understood production path;
- a predictable refactoring whose consumers and mechanical result are known;
- a local deletion or simplification with verified consumers.

These are selection evidence, not file-based eligibility rules. The executor
may instead choose the change workflow when work materially affects contracts,
crosses uncertain boundaries, adds state or dependencies, has unresolved design
choices, or benefits from isolation. Within `orion-review`, consider the simple
workflow first, then choose from the actual repair size and risk.

The primary agent performs the simple workflow directly in the current
worktree and branch:

1. Inspect the repository state and preserve unrelated changes.
2. Derive one concise, stable task name from the user request. This name might be a
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
   commit it immediately, before any later checkpoint edit, using `<task name>:
   <imperative checkpoint summary>`. Keep the task name byte-for-byte identical
   in every checkpoint subject so the commits can be found and squashed together
   later.
8. Do not repeat verification that already passed against the identical staged
   checkpoint. Run a post-commit check only when it validates the commit itself
   or could not run before commit, then continue with the next checkpoint.

A task with one logical checkpoint produces one commit. Do not rewrite or
squash approved checkpoint commits unless the user requests it. If post-commit
verification was actually needed and requires a fix commit, follow the
same-subject rule in `AGENTS.md`.

The simple workflow creates no routing documentation. A surrounding workflow
may still own documentation whose truth changes before or after the simple
implementation, such as a `MODULE_REVIEW.md` finding. You can commit such documentation
separately or altogether on `main` (at the time of finishing task) at the moment required by its ownership rules;
it is not part of the staged implementation gate.


### Change workflow

The **change workflow** is the task-backed isolated path, normally preferred for
task execution and larger, uncertain, cross-boundary, or review-heavy changes.
Choosing it is discretionary; its task-backed mechanics are not. Every change
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

The primary agent owns governing documents and the current execution task's
claim, pause, and completion state on `main`. The worker owns every explicit task
deliverable, including documentation or skills when they are the requested
change, unless a repository rule assigns that particular state to the primary
agent.

### Quick workflow

The **quick workflow** is the fast direct path normally preferred for
documentation, repository rules, skills, task-tree files and `MODULE_REVIEW.md`
reports. It may change any file, including a Makefile, when the executor judges
that a prompt direct edit is proportionate. It also
performs primary-owned documentation commits at lifecycle moments established
by a surrounding simple or change workflow.

Work directly on `main`. Do not create, claim, move or delete a task merely to
route a quick change; do not write an implementation plan for routing,
launch a worker, create a branch or worktree, or add a staged user-review gate.
When the requested documentation change is itself a task-tree operation, apply
`orion-task-runner` to that operation, but create or remove task nodes only when
that is the requested state change or a lifecycle action required by the
surrounding change workflow.

Make the requested edits and commit one coherent state change promptly with a
descriptive single-line subject. Do not split one logical change merely because
it touches both workflow-control and ordinary documentation. Preserve unrelated
changes and keep genuinely unrelated changes in separate commits.

Do not add tests, test coverage or implementation scaffolding solely for the
quick workflow. Verification is optional and chosen by the executor. Run an
existing check only when its value is proportionate to the change; this may be
a lightweight artifact validator, `git diff --check`, a Makefile dry run, or a
Maven or other project test. Do not invent or broaden verification merely to
claim that the change was tested, and do not repeat the same check after commit
when it already passed against the committed content. If no useful check exists,
inspect the diff, commit it, and report that no automated check was applicable.

Within a simple or change workflow, the surrounding workflow determines when a
documentation state is accurate and who owns it. The quick workflow performs
that edit, chosen check and commit without adding an interaction, approval gate,
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
workflow-control changes and normally use the quick workflow. The executor
may choose the simple or change workflow when the actual magnitude justifies its
review or isolation mechanics. During a change workflow, the primary agent owns
the current execution task's state transitions on `main`; a worker may edit an
in-scope file only when that file is an explicit task deliverable rather than
state governing its own execution. Verification remains at the selected
workflow's discretion; an in-scope file neither requires nor forbids Maven or
another project check. Coherent changes may share one commit and do not require
isolation merely because files cross this scope boundary.

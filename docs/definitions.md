# Repository definitions

## Implementation workflows

An **implementation change** modifies source, tests, build files, configuration,
or ordinary product documentation that accompanies such a modification. Before
editing implementation files, select exactly one of the following workflows.
Documentation-only work does not need an implementation workflow; apply its
own repository ownership and commit rules directly.

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
confidently as one staged current-worktree change. Within `orion-review`, prefer
the simple workflow for every repair that satisfies this boundary.

The primary agent performs the simple workflow directly in the current
worktree and branch:

1. Inspect the repository state and preserve unrelated changes.
2. Implement the bounded change and its tests without creating or claiming a
   task, writing a plan merely for routing, launching a worker, or creating a
   branch or worktree.
3. Run the verification required by `AGENTS.md` and review the complete change.
4. Include ordinary product documentation changed with the implementation in
   the same change.
5. Stage only files and hunks owned by the change, show the staged result and
   verification outcome to the user, and ask for review. Do not commit yet.
6. If the user requests corrections, update the implementation and its staged
   result, verify and review it again, and repeat the user gate.
7. After explicit approval, verify that the staged result is the reviewed
   result, commit it with a single-line subject, and run the required
   post-commit verification.

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
workflow-control changes rather than product implementation. Make and commit
them directly on `main`, never in an implementation branch or worktree and never
through an implementation worker. During queued execution, the primary agent
running the change workflow owns task-tree state transitions; otherwise the
primary agent owns the in-scope change. Do not run Maven for an in-scope-only
commit, but still run validation specific to affected skill resources. In-scope
changes from the same requested work may share one commit and do not require
isolation from each other.

---
name: orion-task-runner
description: >-
  Select, plan, and track Orion work in the numbered filesystem task tree rooted
  at docs/plans/TASK.md. Use for requests to take, choose, continue, claim, run,
  or plan tasks, including "возьми задачу", "следующая задача", "продолжай по
  задачам", and "pick a task". Handle task descriptions, ordering, composition,
  dependencies, claims, pauses, and completion independently of the workflow
  chosen to execute a task.
---

# Orion Task Runner

## Roles and Startup

Use this skill directly for selection, planning, task descriptions, ordering,
composition, dependencies, and task tracking. These operations do not select an
execution workflow or launch an implementation worker by themselves. Apply the
[workflow recommendations](../../../docs/definitions.md#repository-workflows):
task execution normally uses `orion-change-workflow`, while the executor may
choose `orion-simple-workflow` or `orion-quick-workflow` when their mechanics are
more proportionate to the actual magnitude and risk. File type and task-tree
membership never force the choice.

The task tree belongs to the repository
[workflow-control scope](../../../docs/definitions.md#workflow-control-scope);
apply its shared ownership rules and use `orion-quick-workflow` for primary-owned
task-tree commits reached during execution.

When an execution skill or its worker reads this skill, apply only the task model
and the rules for that role; do not recursively select or launch another
workflow. The primary agent owns claims, queue moves, pauses, and completion
state on `main`. A change-workflow worker never edits state governing its own
execution. Status, triage, explanation, and planning alone do not start or claim
work. Do not create or claim a task merely because a direct quick or simple
change has no queued task. `orion-change-workflow` is the exception because its
selected mechanics explicitly require create-or-claim state before a worker.

Read `AGENTS.md`, `docs/plans/TASK.md`, relevant ancestor `TASK.md` files and
candidate leaf files, and inspect `git status --short` before choosing work.
Treat existing changes as user-owned unless made in this request. Do not revert,
stage, or absorb unrelated edits.

## Canonical Task Model

`docs/plans/TASK.md` describes the root. `current-work/` and `upcoming-work/`
are unnumbered queue roots, each with a `TASK.md`. Below either queue, immediate
task entries share one locally numbered namespace:

- `NN_slug/` is a composite task described by its own `TASK.md`.
- `NN_slug.md` is an executable leaf task, stored directly beside other leaves
  and composite directories.
- Composites may contain composites at any depth. A composite describes scope,
  aggregate status, dependencies, ownership, and acceptance; it is not itself
  an executable leaf. Any remaining executable scope needs a numbered leaf
  before execution.
- Prefixes are non-negative decimal integers followed by `_`, zero-padded for
  readable directory listings. Compare prefixes numerically, not lexically.
  Prefix values must be unique among all siblings, across files and directories.
  Numbering is local and may have gaps: `01_first.md`, `05_group/`, `20_last.md`.
- Filesystem entries are the only source of child membership and order. Do not
  maintain immediate-child checklists, ordered link indexes, or queue manifests
  in `TASK.md`. Links explaining real dependencies and scope boundaries are fine.

Keep task descriptions short; put detailed designs and implementation steps in
ordinary `docs/plans/` documents. `TASKS.md`, if present, is only a pointer to
`docs/plans/TASK.md`, never a second task list.

## Selection and Ownership

For a generic next-task request, inspect current work first, then upcoming
work if no current leaf is ready. Within each directory, traverse entries in
numeric prefix order and descend into composites recursively. Select the first
unclaimed, dependency-ready leaf in that traversal. An explicit user task or
pool narrows the candidates; it does not bypass ownership or prerequisites.

Check both the leaf and its ancestors for claims. An `Owner:` line (including
`Owner: codex`) or an explicit `in progress`, `started`, `paused`, `Current work`,
or `Active next task` marker makes that node occupied. Queue-level `Status:
active` describes a queue, not an exclusive claim. A claimed composite makes
its descendants unavailable. Inspect other worktrees and local branches when
the selected workflow uses them; a branch-local claim also counts. Re-resolve
renamed or moved paths through Git history and task content before deciding
that an owner or prerequisite disappeared.

Never take over or rewrite an existing claim unless the user explicitly names
the task and authorizes takeover. Do not treat an old timestamp as release.
Honor explicit dependencies, deferred state, and cross-task coordination gates.
Missing task files alone do not prove completion: check integration evidence.
Skip blocked leaves only for genuinely independent ready work; never skip a
prerequisite to execute its dependent. Numeric order is selection priority, not
an implicit dependency between otherwise independent tasks.

Report the exact blocker when no ready leaf remains. Clarify only when the
requested task identity is ambiguous or the next action requires new authority.

## Planning and Queue Insertion

Place new work in the appropriate existing composite or queue, assigning a
prefix that inserts it at the intended local execution position. Prefer safe
deletion or consolidation over additive work when planning equally ready work,
while preserving dependencies. Execution follows the resulting numeric order.
Use a free number in an existing gap when possible; do not renumber merely to
close gaps after deletion. If insertion requires renumbering, change only the
necessary siblings and update their references together. Resolve claims across
worktrees/branches first and do not invalidate another session's active paths.

Use a leaf file for a bounded executable task and a numbered directory with
`TASK.md` for a composition. When splitting a leaf, put its executable scope
in numbered children and keep the aggregate description in the composite.
Do not duplicate the work as an executable parent and executable descendants.

Use `orion-quick-workflow` to commit a coherent task-tree state change directly
on `main` as soon as it becomes accurate. Do not claim planned work. When queued
execution starts under any workflow, the primary agent moves only the selected
leaf when required, records the claim, and updates mechanically affected
references in the same commit. Preserve aggregate context and dependencies.
Remove an emptied source composite only when its remaining scope is accounted
for. The quick workflow does not add a review gate or mandatory verification to
this state commit; any useful check remains at the executor's discretion.

Plan replacements around one canonical production path: update every real
in-repository consumer and remove replaced internal APIs, state, configuration,
and branches in the same task. Preserve required runtime behavior and explicit
wire/persisted contracts. Apply `AGENTS.md` to legacy-only test removal; tests
and hypothetical consumers do not justify a second production path.

## Choose execution and claim

After selecting a leaf, choose `orion-simple-workflow`,
`orion-change-workflow`, or `orion-quick-workflow` from the workflow definitions
and actual work. Change is the default recommendation, not a requirement.

Before executing a selected queued leaf, record the owner, stable session
identity, and local start time in its claim and commit the state through
`orion-quick-workflow`. Workflow selection is runner state, not task content:

```markdown
- [ ] Task title and short context.
  - Owner: codex, session SESSION_ID, started YYYY-MM-DD HH:MM Europe/Amsterdam.
```

For `orion-change-workflow`, also choose collision-free branch and worktree
names before the claim and record them exactly:

```markdown
  - Owner: codex, session SESSION_ID, branch `codex/CHANGE_SLUG`,
    worktree `.worktrees/CHANGE_SLUG`, started YYYY-MM-DD HH:MM Europe/Amsterdam.
```

Use the actual session ID when available; otherwise generate one short unique ID
and reuse it. If the claim cannot be isolated from unrelated changes, report the
conflict without starting execution. Under change workflow, the exact committed
HEAD containing the claim is the worker base and the worker must use the recorded
branch and worktree. Under simple or quick workflow, return to that workflow
after the claim commit and work directly on `main` as it specifies.

The chosen execution skill owns edits, checks, review gates, and implementation
commits. Apply `orion-minimal-implementation` whenever implementation or review
work requires it. This runner owns only task-tree state.

## Completion and pause

Complete task-tree state only after the chosen workflow has reached its own
completion condition: approved checkpoint commits and required verification for
simple workflow; the committed result for quick workflow; or verified
integration and worktree/branch cleanup for change workflow.

Then use `orion-quick-workflow` to delete the completed leaf, walk upward, and
remove completed empty composite directories in full, including their `TASK.md`,
only when aggregate acceptance and remaining scope are satisfied. Preserve a
parent with unfinished siblings, both queue roots, and root `docs/plans/TASK.md`.
Remove outstanding-work entries and replace still-needed dependency references
with verified completion evidence. Retain useful evidence in ordinary plans or
reviews. Do not keep completed nodes or renumber siblings to close gaps. Commit
this coherent completion state separately from preceding execution commits.

When execution pauses incomplete work, its owner reports the next step without
editing the task tree. The primary agent records that state on `main` in the
existing claim using the same session identity, then commits it
through `orion-quick-workflow`:

```markdown
  - Owner: codex, session SESSION_ID, paused YYYY-MM-DD HH:MM Europe/Amsterdam;
    next: brief next step.
```

Report the task name and leaf path explicitly. Provide the required
`orion-minimal-implementation` summary when that skill applied: what was solved,
how, which parts changed, actual verification, and remaining work. Mention
unrelated pre-existing working-tree changes.

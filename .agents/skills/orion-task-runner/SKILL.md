---
name: orion-task-runner
description: >-
  Select, plan, and track Orion work in the numbered filesystem task tree rooted
  at docs/plans/TASK.md. Use for requests to take, choose, continue, claim, run,
  or plan tasks, including "возьми задачу", "следующая задача", "продолжай по
  задачам", and "pick a task". Handle task descriptions, ordering, composition,
  and dependencies directly; route implementation through orion-change-orchestrator.
---

# Orion Task Runner

## Roles and Startup

Use this skill directly for selection, planning, task descriptions, ordering,
composition, dependencies, and task tracking. These edits do not launch an
implementation worker. Every task execution
must use [orion-change-orchestrator](../orion-change-orchestrator/SKILL.md),
which owns worker launch, review, and the integration gate. The implementation
worker must apply [orion-minimal-implementation](../orion-minimal-implementation/SKILL.md).

When the orchestrator or its assigned worker reads this skill, apply the task
model and the rules for that role; do not invoke the orchestrator recursively
or spawn another implementation worker. The primary selects; the assigned
worker claims and implements only its selected leaf. Status, triage, explanation,
and planning alone do not start execution or claim work. Direct repository
changes without a queued task go to the change orchestrator with the user request
as their scope; do not create or claim a task merely to execute them.

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
its descendants unavailable. Inspect other worktrees and local branches as
required by the orchestrator; a branch-local claim also counts. Re-resolve
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

Commit newly created task-tree changes immediately as a documentation-only
commit, as required by `AGENTS.md`. Do not claim planned work. When a worker
starts upcoming work, move only its selected leaf to the appropriate current
queue/composite, choose a free local prefix, and update affected references in
the same isolated claim change. Preserve required aggregate context and
dependencies in the moved leaf. Remove an emptied source composite only when
its remaining scope is accounted for.

Plan replacements around one canonical production path: update every real
in-repository consumer and remove replaced internal APIs, state, configuration,
and branches in the same task. Preserve required runtime behavior and explicit
wire/persisted contracts. Apply `AGENTS.md` to legacy-only test removal; tests
and hypothetical consumers do not justify a second production path.

## Worker Claim and Execution

Before substantial edits, the assigned worker updates only the selected
unclaimed leaf file and any mechanical task-path references required by its
authorized queue move in its dedicated worktree. Store the claim in the leaf:

```markdown
- [ ] Task title and short context.
  - Owner: codex, session SESSION_ID, started YYYY-MM-DD HH:MM Europe/Amsterdam.
```

Use the current local time and a stable session identifier. Use the actual
session ID when available; otherwise generate a short unique ID once and reuse
it. Immediately commit the isolated claim and any required queue move before
implementation. Stage only changes made to start that task; do not run tests
for the documentation-only claim commit. If the claim cannot be isolated,
report the conflict without starting implementation.

Read the referenced plans and apply `orion-minimal-implementation` before and during
implementation, including its final self-review. Follow the orchestrator and
`AGENTS.md` for tests, verification, review fixes, commits, and integration.

## Completion and Pause

After implementation, verification, and clean review, the worker squashes its
work while retaining the task leaf and claim. The primary coordinator from
`orion-change-orchestrator` then deletes the completed leaf file and amends the
same commit in the dedicated task worktree. Only the coordinator performs this
completion cleanup; the worker does not delete the task from the queue or plans.

The coordinator walks upward and removes completed
empty composite directories in full, including their `TASK.md`, only when
aggregate acceptance and remaining scope are satisfied. Preserve a parent
with unfinished siblings, both queue roots, and the root `docs/plans/TASK.md`.
The coordinator removes the task's outstanding-work entries from active plans. Replace
still-needed dependency references with verified completion evidence so plans
do not retain dangling links or continue scheduling completed work. Retain useful
completion evidence in ordinary plans or reviews.
Do not keep completed task nodes or renumber remaining siblings to close gaps.

Follow the orchestrator's final review and user integration gate. A prepared
branch is awaiting integration, not a completed task; `AGENTS.md` governs
transfer, required verification, and worktree/branch cleanup.

When pausing incomplete work, retain the leaf and record the next step in its
existing claim using the same session identity:

```markdown
  - Owner: codex, session SESSION_ID, paused YYYY-MM-DD HH:MM Europe/Amsterdam; next: brief next step.
```

Report the task name and leaf-file path explicitly. Provide the required
`orion-minimal-implementation` summary: what was solved, how it was solved, which parts
changed and what changed in each, and actual verification results with any
remaining work. Mention unrelated pre-existing working-tree changes.

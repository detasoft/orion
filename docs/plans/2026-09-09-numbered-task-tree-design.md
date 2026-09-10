# Numbered Filesystem Task Tree

## Goal

Make the filesystem layout the single source of truth for task composition and
execution order. Keep task selection easy to inspect without duplicating child
order in parent documents.

## Canonical Structure

`docs/plans/TASK.md` remains the root index. `current-work/` and
`upcoming-work/` remain unnumbered queue roots.

Every task entry below a queue root starts with a local execution number:

- `NN_name/` is a composite task. Its `TASK.md` describes the aggregate scope,
  status, ownership constraints, and relevant plans.
- `NN_name.md` is an executable leaf task.

Composite task directories may be nested without a depth limit. Numbering is
local to the containing directory, and gaps are allowed, for example
`01_first_stage.md` followed by `05_something_else.md`. Numeric prefixes must be
unique among sibling task entries.

A composite `TASK.md` does not maintain a duplicate child checklist. Its
immediate numbered files and directories define both its children and their
default order. Explicit dependencies and ownership still determine readiness;
the lowest-numbered ready entry is selected.

## Planning and Completion

Planning inserts a new task into its existing sibling queue with a number that
expresses the intended order. Existing entries are not renumbered merely to
make numbering contiguous.

Completing a leaf deletes its Markdown file. A completed composite is deleted
after its children are gone and its aggregate acceptance conditions are met.
No completed task nodes are retained in the active filesystem queue.

## Execution

`orion-task-runner` owns discovery, selection, planning placement, and the
canonical task-tree rules. Executing a selected leaf normally uses
`orion-change-workflow`, but the executor may choose another workflow from the
actual change. Change-workflow implementation workers must apply
`orion-minimal-implementation` before and during implementation, including its
final self-review and required change summary.

The selected workflow must accept a numbered leaf as the executable task
identity and preserve its path through claim and review. `orion-task-runner`
removes the leaf and completed empty composite ancestors only after the selected
workflow reaches its completion condition.

## Migration

Convert the current task tree atomically to the canonical layout:

- assign local numeric prefixes to task directories and leaf files according
  to the established task order and dependency map;
- replace leaf directories containing only `TASK.md` with numbered Markdown
  files;
- keep composite descriptions in their numbered directories and remove their
  duplicated child checklists;
- update active references to the canonical paths;
- update both task skills in the same change so no production workflow relies
  on the previous layout.

The resulting skills describe only the canonical model and contain no
transition mode, compatibility path, or migration history.

## Verification

Validate both skills with the skill validator. Check the task tree for these
invariants:

- every task entry below a queue root has a numeric prefix;
- every composite directory contains `TASK.md` and at least one numbered task
  child unless explicitly retained as an empty queue boundary;
- no leaf task remains as a directory containing only `TASK.md`;
- sibling task prefixes are unique;
- Markdown links under active task, plan, review, and skill documentation
  resolve to the migrated paths;
- neither task skill describes or accepts the previous layout.

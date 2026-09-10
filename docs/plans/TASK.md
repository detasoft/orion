# Orion Task Tree

Status: active

This filesystem tree is the source of truth for current and upcoming Orion work.
The unnumbered queue roots are [current work](current-work/TASK.md) and
[upcoming work](upcoming-work/TASK.md).

Below either queue, `NN_slug/` is a composite task with its own `TASK.md`,
and `NN_slug.md` is an executable leaf. Composites may nest to any depth.
Numeric prefixes are local to each directory, unique across sibling files and
directories, and may have gaps. Directory entries alone define child membership
and order; parent files describe scope, dependencies, ownership, and acceptance.

For the next task, traverse current work recursively in numeric sibling order
and select the first unclaimed, dependency-ready leaf. Use upcoming work if no
current leaf is ready, including required prerequisites. Check the leaf,
ancestors, other worktrees, and branch history for ownership and integration
evidence; an old owner timestamp or missing file does not prove release or
completion. The [dependency and coordination audit](2026-09-09-task-stream-order.md)
records cross-task gates; its snapshot labels are not a separate queue order.

Use `orion-task-runner` for selection, planning, execution routing, task
descriptions, ordering, composition, dependencies, and lifecycle state. The
runner chooses the execution workflow; do not encode that choice in task
content.
Insert planned tasks by their intended local ordinal. Delete completed leaves
and completed empty compositions after verification and review; retain these
queue roots. Keep detailed designs and completion evidence in ordinary
`docs/plans/` documents. Do not maintain a second task list in `TASKS.md`.

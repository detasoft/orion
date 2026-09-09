# Orion Task Tree

Status: active
Source: converted from the former root task list on 2026-08-05.

This directory tree is the source of truth for current and upcoming Orion work.
Each task directory contains its own `TASK.md`. A task directory may also
contain child task directories.

## Queues

- [Current work](current-work/TASK.md)
- [Upcoming work](upcoming-work/TASK.md)

## Execution Order

Use the [stream order and dependency map](2026-09-09-task-stream-order.md)
to choose work across the two queues. Queue location records commitment;
stream order records sequencing. Existing task paths and owners remain canonical.

Next overall, if still unclaimed:
[Migrate the remaining Orion key owners](current-work/unified-key-material-bootstrap/remaining-key-owner-migration/TASK.md).
Its material and identity prerequisites are integrated, and SSH host keys still
use independent files. Finish that consolidation before adding new key flows.

Independent next work in the Agent control stream:
[Persist agent and launch records](current-work/agent-session-server/control-and-registries/agent-and-launch-records/TASK.md).
The AgentD handshake can proceed alongside the server record work against the
existing identity protocol.

Recheck ownership, prerequisites, and the working tree before each selection.
Skip occupied work, including children of an occupied parent; do not reclaim
it because an owner timestamp is old. Among dependency-ready current tasks,
prefer deletion or consolidation, then the stream order in the linked queues.

## Rules

- Do not maintain a separate root `TASKS.md` task list.
- Keep each `TASK.md` focused on status, scope, ownership, and immediate child
  tasks.
- Keep detailed designs and implementation steps in ordinary plan files under
  `docs/plans/`.

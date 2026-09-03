# Simplify Git Wire Architecture

Status: todo
Plan: [Git wire architecture simplification](../../2026-09-03-git-wire-architecture-simplification.md)

Finish the blocking wire migration and restore clear ownership between the
wire core, Orion server orchestration, and native repository storage.

## Child Tasks

- [ ] [Make `GitObjectId` canonical](canonical-git-object-id/TASK.md)
- [ ] [Complete blocking output migration](blocking-output-migration/TASK.md)
- [ ] [Separate `git-parser` from native storage](parser-storage-boundary/TASK.md)
- [ ] [Reuse one repository command context](repository-command-context/TASK.md)
- [ ] [Add a global capability advertisement policy](global-capability-advertisement-policy/TASK.md)
- [ ] [Re-audit the simplified Git wire architecture](post-simplification-review/TASK.md)

## Recommended Order

1. Make object identity canonical before moving the types that consume it.
2. Remove the remaining resumable output shapes before splitting wire and
   server output ownership.
3. Establish the parser/storage module boundary.
4. Bind one repository context at each bootstrap entrypoint.
5. Install the global capability policy at those composition points.
6. Re-run the architecture review and update the saved baseline report.

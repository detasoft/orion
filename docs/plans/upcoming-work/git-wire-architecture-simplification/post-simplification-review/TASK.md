# Re-audit the Simplified Git Wire Architecture

Status: todo
Depends on: all preceding sibling tasks
Baseline: [Git parser architecture simplification review](../../../../reviews/2026-09-03-git-parser-architecture-simplification.md)

Repeat the read-only architecture simplification review only after the object
ID, blocking output, parser/storage boundary, repository context, and
capability policy tasks are complete.

## Scope

- Re-run `architecture-simplifier` over `git-parser`, `git-native-storage`,
  `git-client`, `net/git-transport`, and the native Git HTTP integration.
- Verify every accepted baseline finding against source, Maven dependency
  graphs, tests, and bootstrap composition points.
- Inspect the completed blocking output path for additional removable state,
  wrappers, encoded collections, or coordination layers.
- Preserve structured wire-error classification unless evidence shows that it
  is unused by both programmatic flows and server logging/diagnostics.
- Update the baseline report with `resolved`, `remaining`, or `regressed` status
  and concrete evidence for every finding.
- Create separate follow-up task nodes for newly confirmed simplifications;
  do not implement them as part of this read-only audit.

## Completion Criteria

- All five prerequisite task nodes are complete.
- The saved report contains the post-implementation evidence and conclusion
  for every baseline finding.
- Any remaining or newly discovered work is represented in the task tree.

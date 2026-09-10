# Re-audit the Simplified Git Wire Architecture

Status: todo
Depends on: all preceding sibling tasks,
[native Git storage architecture review](../03_native-storage-architecture-review.md)
Baseline: [Git parser architecture simplification review](../../../../reviews/2026-09-03-git-parser-architecture-simplification.md)

Repeat the read-only architecture simplification review only after the object
ID, blocking output, parser/storage boundary, repository context, and
capability policy tasks are complete.

## Scope

- Audit `git-parser`, `git-client`,
  `net/git-transport`, and the native Git HTTP integration. Reuse the native
  storage review and inspect `git-native-storage` here only where needed to
  verify cross-module integration and regressions.
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

## Detailed implementation plan

### Task 7: Verify the complete simplification and update the review

1. Run focused reactor tests for `git-parser`, `git-native-storage`,
   `git-client`, `net/git-transport`, `net/http-core`, and
   `tests/git-engine-orion-adapters` with `-Pdev -T 4 -am`.
2. Run:

   ```bash
   mvn verify -Pdev -T 4
   ```

   Expected: BUILD SUCCESS.
3. Run `git diff --check` and the repository source line-length audit; fix only
   issues introduced by this work.
4. Confirm the parser dependency and import checks from Task 4 remain empty.
5. Review the final diff against all five task nodes, especially producer close
   ownership, action-specific authorization, and default byte compatibility.
6. Complete the read-only task at
   `docs/plans/tasks/11_git/01_wire-architecture-simplification/06_post-simplification-review.md`
   without implementation changes after all five implementation tasks are done.
7. Update
   `docs/reviews/2026-09-03-git-parser-architecture-simplification.md` with a
   dated `resolved`, `remaining`, or `regressed` conclusion and evidence for
   every finding.
8. Create separate task nodes for any remaining or newly confirmed
   simplification. Do not implement those findings during the read-only audit.

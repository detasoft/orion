# Review stale branches and worktree cleanup candidates

Status: in progress
Report: ../../../reviews/2026-09-05-stale-branches-and-worktrees.md

- [x] Compare all six candidates with main, including replacement behavior,
  regression tests, worktree state and ownership; record evidence and recommendations.

- [ ] Clean up reviewed candidates, starting with integrated local branches.
  - Owner: codex, session cleanup-2dcc1a3d, started 2026-09-05 20:21 Europe/Amsterdam.

`work` is eligible for removal. Retain both paused task worktrees until their
owners release them. Retain the July Git branches for useful regression
scenarios and `transfer` for unique infrastructure files. The report records
conditions for later cleanup; no branches or worktrees were deleted.

Deletion of unique work requires explicit approval. Recheck refs, local files
and ownership immediately before any authorized cleanup. Other worktrees
remain outside this task's scope.

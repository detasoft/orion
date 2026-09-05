# Review stale branches and worktree cleanup candidates

Status: paused
Report: ../../../reviews/2026-09-05-stale-branches-and-worktrees.md

- [x] Compare all six candidates with main, including replacement behavior,
  regression tests, worktree state and ownership; record evidence and recommendations.

- [ ] Clean up reviewed candidates, starting with integrated local branches.
  - Owner: codex, session cleanup-2dcc1a3d, paused 2026-09-05 21:21 Europe/Amsterdam;
    next: preserve the two close-failure regression scenarios from
    `native-git-client-session-machines` in the canonical blocking client.

- [x] Remove local `work` after rechecking ancestry and worktree registration.
  Removed at `a51cfe9b`; zero commits outside main; remote refs unchanged.

Retain both paused task worktrees until their
owners release them. Retain the July Git branches for useful regression
scenarios and `transfer` for unique infrastructure files. The report records
conditions for later cleanup. Local `work` is deleted; this cleanup did not
remove any worktrees.

Deletion of unique work requires explicit approval. Recheck refs, local files
and ownership immediately before any authorized cleanup. Other worktrees
remain outside this task's scope.

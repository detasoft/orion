# Review stale branches and worktree cleanup candidates

Status: upcoming

- [ ] Determine which remaining local branches and worktrees can be removed
  without losing useful work or interrupting active sessions.

## Candidates

Snapshot from 2026-09-05; recheck all refs and worktree state before acting.

- [ ] `work`: contained in `main`, no registered worktree.
- [ ] `codex/primary-upstream-sync-6e3b` and
  `.worktrees/primary-upstream-sync-6e3b`: contained in `main`, clean at inspection.
- [ ] `codex/agentd-command-orchestration-d8e4` and
  `.worktrees/agentd-command-orchestration-d8e4`: contained in `main`, clean at
  inspection; last commit pauses the task, so verify ownership and preserve
  unfinished task tracking rather than treating cleanup as task completion.
- [ ] `codex/git-wire-response-parsers`: last commit 2026-07-27; 50 commits
  outside `main` ancestry, no registered worktree.
- [ ] `native-git-client-session-machines`: last commit 2026-07-27; 47 commits
  outside `main` ancestry, no registered worktree.
- [ ] `transfer`: last commit 2026-05-11; 129 commits outside `main` ancestry,
  no registered worktree.

## Review and Safety

- Start with the two July Git branches. Compare behavior and relevant tests
  against current `main`, accounting for squash/cherry-pick transfers and
  replacement implementations; ancestry counts alone do not prove missing work.
- Record per candidate what is integrated, superseded, or still useful, with
  supporting commit/file references and a retain/remove recommendation.
- Obtain approval before deleting branches with unique work. Recheck clean
  worktrees and active ownership immediately before any authorized cleanup.
- Other worktrees are not cleanup targets in this task: interactive-terminal,
  journal-durability, linux-process-tree-control, organization-users-roles,
  query-and-output, and session-replication have unique commits. The Linux
  worktree also had uncommitted changes at inspection. Do not disturb them.
- Verify actual worktree paths with `git worktree list`; this checkout uses
  `.worktrees`, and `.workspaces` was absent at inspection.

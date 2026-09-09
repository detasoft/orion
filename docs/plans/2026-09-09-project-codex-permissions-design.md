# Project Codex Permissions Design

## Goal

Keep Orion's recurring command approvals in a tracked project configuration so
they apply only when the repository is trusted and are shared through Git.

## Design

Add `.codex/rules/default.rules` with the smallest set of prefixes requested for
the Orion workflow: direct `make` commands, `mvn verify`, staging and committing,
cached-diff checks, cherry-picking, branch deletion, and worktree management.
Remove the same permissions from the active user rules after the project change
is integrated so the commands are no longer approved globally.

Codex prefix rules match complete argument tokens and cannot restrict a branch
or path argument with a glob. The `git branch -D` and `git worktree` permissions
are therefore technically command-wide; Orion's existing `codex/*` branch and
`.worktrees/` conventions define their intended operational scope.

Shell redirection prevents Codex from decomposing a command into independently
matched prefixes. The project rule permits every direct `make ...` invocation,
but does not permit arbitrary `zsh -lc` commands merely to cover `make ... > ...`.
Commands should normally rely on Codex output capture instead of redirection.

## Verification

Use `codex execpolicy check` with the project and active user rule files to
verify every requested direct command resolves to `allow`. Confirm the project
file is tracked and unrelated working-tree changes remain untouched.

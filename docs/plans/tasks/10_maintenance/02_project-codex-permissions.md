# Project Codex Permissions Implementation Plan

**Goal:** Track Orion-specific Codex command permissions in the repository.

**Architecture:** Add one project-local `.rules` file using existing Codex
`prefix_rule` entries. Keep the file declarative and verify behavior with
`codex execpolicy check`; no product code, build logic, or test suite changes are
needed.

**Tech Stack:** Codex Starlark rules, Git

---

### Task 1: Add the project rules

**Files:**

- Create: `.codex/rules/default.rules`

**Step 1: Create the minimal rule set**

Add allow rules for these prefixes:

- `make`
- `mvn verify`
- `git add`
- `git commit`
- `git diff --cached --check`
- `git diff --cached --name-status`
- `git cherry-pick`
- `git branch -D`
- `git worktree`

Do not add a shell-wide rule for redirected commands.

**Step 2: Validate representative commands**

Run `codex execpolicy check --pretty --rules .codex/rules/default.rules -- ...`
for every listed prefix, including `codex/example` and `.worktrees/example`
arguments for the destructive Git commands.

Expected: every direct command reports `"decision": "allow"`.

**Step 3: Inspect the change**

Run:

```bash
git diff --check
git status --short
```

Expected: only `.codex/rules/default.rules` is changed in the implementation
worktree and no whitespace errors are reported.

### Task 2: Remove duplicated user rules after integration

**Files:**

- Modify: `/Users/vi/work/.codex/deta/rules/default.rules`

After the project commit is integrated, remove only the equivalent user-layer
rules so Orion permissions are no longer global. Re-run representative
`codex execpolicy check` commands with both active rule files.

---

## Project Codex Permissions Design

### Goal

Keep Orion's recurring command approvals in a tracked project configuration so
they apply only when the repository is trusted and are shared through Git.

### Design

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

### Verification

Use `codex execpolicy check` with the project and active user rule files to
verify every requested direct command resolves to `allow`. Confirm the project
file is tracked and unrelated working-tree changes remain untouched.

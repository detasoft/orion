# Project Codex Permissions Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

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

**Step 4: Commit**

```bash
git add -- .codex/rules/default.rules
git commit -m "Track project-scoped Codex permissions"
```

### Task 2: Remove duplicated user rules after integration

**Files:**

- Modify: `/Users/vi/work/.codex/deta/rules/default.rules`

After the project commit is integrated, remove only the equivalent user-layer
rules so Orion permissions are no longer global. Re-run representative
`codex execpolicy check` commands with both active rule files.

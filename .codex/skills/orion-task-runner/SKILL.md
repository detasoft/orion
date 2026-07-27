---
name: orion-task-runner
description: Coordinate Orion repository work from TASKS.md. Use when the user asks Codex to take, choose, continue, claim, or run a task from TASKS.md in the Orion repo, including prompts like "возьми задачу", "следующая задача", "продолжай по TASKS", "pick a TASKS.md item", or requests that rely on the current high-level task list.
---

# Orion Task Runner

## Overview

Use this skill to choose one high-level Orion task from `TASKS.md`, mark it as owned before substantial edits, execute it under the repository rules, and keep `TASKS.md` current without turning it into a detailed plan.

## Startup

1. Confirm the working directory is the Orion repository.
2. Read `AGENTS.md`, `TASKS.md`, and `git status --short` before choosing work.
3. Treat existing uncommitted changes as user-owned unless you made them in the current request. Do not revert or stage unrelated changes.
4. If the user asks only for status, triage, or explanation, do not claim a task.

## Task Selection

Prefer this order:

1. A task the user explicitly names or describes.
2. An unchecked task in `## Current` that says `Active next task`.
3. The first unchecked task in `## Current` that matches the user's request.
4. The first unchecked task in `## Next`, moved or copied into `## Current` only when starting it.

Ask a concise question before proceeding when two or more tasks match equally well, when the requested work is absent from `TASKS.md`, or when the matching task already has a non-Codex owner line.

## Claim Format

Before substantial code, doc, or test edits, update only the selected task block in `TASKS.md`:

```markdown
- [ ] Task title and short context.
  - Owner: codex, started YYYY-MM-DD HH:MM Europe/Amsterdam.
```

Use the current local date and time. If a Codex owner line already exists for the selected task, update that line instead of adding another one. Keep the file high-level; put detailed design or implementation notes in `docs/plans/`.

## Execution Rules

1. Follow `AGENTS.md` for Maven profiles, commit behavior, tests, comments, and `TASKS.md` scope.
2. Read any referenced plan under `docs/plans/` before changing related code.
3. Add or extend tests when changing functionality.
4. Run focused verification during implementation and the appropriate Maven check before claiming completion.
5. Keep `TASKS.md` edits scoped to the selected task and a small number of upcoming high-level tasks.

## Finish

When the selected task is fully implemented and verified, mark it complete and remove the owner line. Add or adjust the next high-level task only if needed.

When stopping with work incomplete, keep the task unchecked and replace or update the owner line with a short status line:

```markdown
  - Owner: codex, paused YYYY-MM-DD HH:MM Europe/Amsterdam; next: brief next step.
```

In the final response, name the selected task, summarize code changes, list verification run, and mention any unrelated pre-existing working tree changes.

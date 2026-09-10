# Orion Implementation Workflows Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the single change orchestrator with explicit simple, task-backed change, and document workflows.

**Architecture:** `docs/definitions.md` owns the shared selection boundary and documentation-commit semantics.
Three repository-local skills own execution: `orion-simple-workflow` for staged current-worktree changes,
`orion-change-workflow` for task-backed worker changes, and `orion-document-workflow` for documentation-only
changes. Task and review skills keep their domain ownership and route work through the applicable workflow.

**Tech Stack:** Markdown skill instructions, Git workflow, repository skill validator.

---

### Task 1: Define the shared workflow contract

**Files:**
- Modify: `docs/definitions.md`

**Step 1: Add implementation workflow selection**

Define a decision boundary that selects the simple workflow only for bounded, predictable changes and selects the
change workflow for task-backed, contract-affecting, cross-module, lifecycle, concurrency, or materially uncertain
work. Route documentation-only work through the document workflow.

**Step 2: Define documentation timing**

Distinguish ordinary product documentation included with implementation from primary-owned workflow-control and
governing documentation committed on `main` when it becomes accurate.

**Step 3: Check and commit**

Run `git diff --check`, inspect the definitions diff, and commit only `docs/definitions.md` with a one-line subject.
Do not run Maven because the commit is documentation-only.

### Task 2: Create and verify `orion-simple-workflow`

**Files:**
- Create: `.agents/skills/orion-simple-workflow/SKILL.md`

**Step 1: Establish the baseline failure**

Give an independent read-only agent a realistic small POM, Makefile, bug-fix, or predictable-refactor request under
the current repository rules. Confirm that the existing instructions route it to a worker or otherwise fail to
provide the staged user-review gate.

**Step 2: Write the minimal skill**

Require current-worktree implementation, no routing task or plan, proportional verification, staging only the owned
checkpoint, self-review, user review of the staged diff, and a commit only after explicit approval. Choose coherent
checkpoints during the task and give every subject one stable task-name prefix plus its checkpoint summary so the
commits can be found and squashed together. Preserve separate milestone documentation commits owned by a surrounding
review workflow.

**Step 3: Validate behavior and structure**

Run the same scenario with the new skill and confirm the agent stops with the implementation staged for user review.
Run `quick_validate.py` for the skill and `git diff --check`.

**Step 4: Commit the verified skill**

Commit only the new skill with a one-line subject. Do not run Maven.

### Task 3: Create and verify `orion-change-workflow`

**Files:**
- Create: `.agents/skills/orion-change-workflow/SKILL.md`

**Step 1: Establish the baseline failure**

Give an independent read-only agent a direct non-trivial implementation request under the current orchestrator.
Confirm that its direct-change mode permits worker launch without first creating or claiming and committing a task.

**Step 2: Write the minimal skill**

Move the worker, worktree, review, user integration gate, integration verification, cleanup, and context-boundary
guarantees from `orion-change-orchestrator`. Require a committed task creation or claim before every worker launch.
Keep task-tree edits and governing workflow documents primary-owned on `main` at their accurate lifecycle moments.

**Step 3: Validate behavior and structure**

Run the same scenario with the new skill and confirm it creates or claims a task before worker launch. Run
`quick_validate.py` for the skill and `git diff --check`.

**Step 4: Commit the verified skill**

Commit only the new skill with a one-line subject. Do not run Maven.

### Task 4: Create and verify `orion-document-workflow`

**Files:**
- Create: `.agents/skills/orion-document-workflow/SKILL.md`

**Step 1: Establish the baseline failure**

Give an independent read-only agent a coherent documentation-only request touching an ordinary document and an
existing task description. Confirm that current ownership rules split it into multiple paths or commits.

**Step 2: Write the minimal skill**

Require direct primary-owned work on `main`, no routing task or worker, one prompt commit per coherent documentation
state, no tests or Maven, and only cheap artifact-specific validation when it exists and matters. Preserve lifecycle
timing when this workflow performs a documentation commit inside a surrounding simple or change workflow.

**Step 3: Validate behavior and structure**

Run the same scenario with the new skill and confirm one direct commit with no extra gate or tests. Run
`quick_validate.py` for the skill and `git diff --check`.

**Step 4: Commit the verified skill**

Commit only the new skill with a one-line subject. Do not run Maven.

### Task 5: Migrate workflow consumers and remove the orchestrator

**Files:**
- Modify: `AGENTS.md`
- Modify: `.agents/skills/orion-task-runner/SKILL.md`
- Modify: `.agents/skills/orion-review/SKILL.md`
- Delete: `.agents/skills/orion-change-orchestrator/SKILL.md`
- Modify: `docs/plans/TASK.md`
- Modify: affected `docs/plans/*.md` references

**Step 1: Route repository changes through the definitions**

Update `AGENTS.md` to use the shared workflow selection rule. Keep workflow-control ownership and dedicated-worktree
rules attached to the workflow that actually uses them.

**Step 2: Narrow task and review responsibilities**

Make task execution always use `orion-change-workflow`. Make review repairs prefer `orion-simple-workflow` and use
`orion-change-workflow` only when the definitions exclude the repair from the simple workflow, rather than embedding
a third implementation path.

**Step 3: Update every real reference and delete the old skill**

Replace active and historical instructional references so no repository consumer points at the removed skill, then
delete `.agents/skills/orion-change-orchestrator/`.

**Step 4: Verify the complete migration**

Run both skill validators, `rg` for stale orchestrator references, Markdown-link checks available in the repository,
`git diff --check`, and inspect the complete diff against the design. Do not run Maven because all changes are
documentation and workflow-control files.

**Step 5: Commit the migration**

Commit the migrated consumers and deletion with one single-line subject.

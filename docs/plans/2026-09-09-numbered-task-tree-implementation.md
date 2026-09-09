# Numbered Filesystem Task Tree Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the Orion task tree with locally numbered composite directories and leaf Markdown files, and require orchestrated execution with `minimal-implementation`.

**Architecture:** The filesystem is the only child-order source: numbered directories contain composite `TASK.md` descriptions and numbered Markdown files are executable leaves. The task runner selects and plans work, while `orion-review-orchestrator` exclusively executes selected leaves through workers that apply `minimal-implementation`.

**Tech Stack:** Markdown task files, Git path history, Codex skills, repository link and skill validators.

---

### Task 1: Capture the Current Skill Failure

**Files:**

- Read: `.agents/skills/orion-task-runner/SKILL.md`
- Read: `.agents/skills/orion-review-orchestrator/SKILL.md`
- Record evidence in the implementation session summary; do not add a permanent fixture.

**Step 1: Define the pressure scenario**

Give an independent agent a hypothetical task subtree containing both
`01_composite/TASK.md` and leaf files such as `01_first.md` and `05_last.md`.
Ask it to select, claim, and explain how it would execute the next task using
the unmodified skills.

**Step 2: Run the scenario against the current skills**

Expected: the agent either ignores leaf Markdown files, expects every task to
live in a `TASK.md`, duplicates order through parent checklists, or executes
without making both `orion-review-orchestrator` and `minimal-implementation` mandatory.

**Step 3: Preserve the exact failure modes**

Use the observed failures to keep the skill edit narrow. Do not add rules for
hypothetical behavior that the scenario did not expose and the approved design
does not require.

### Task 2: Build the Canonical Path Mapping

**Files:**

- Read: `docs/plans/TASK.md`
- Read: `docs/plans/current-work/TASK.md`
- Read: `docs/plans/upcoming-work/TASK.md`
- Read: `docs/plans/2026-09-09-task-stream-order.md`
- Read: every descendant `TASK.md` below `docs/plans/current-work/` and
  `docs/plans/upcoming-work/`

**Step 1: Classify every task node**

Treat a directory with immediate task children as a composite. Treat a
directory without task children as a leaf, even when other non-task documents
refer to it.

**Step 2: Assign local prefixes**

Within each parent, preserve explicit child order from its current `TASK.md`.
Use the established stream-order document to resolve any root ordering that is
not represented by one flat list. Assign unique, zero-padded local prefixes;
do not require numbering to be contiguous after future edits.

**Step 3: Check live ownership before renaming**

Run `git status --short`, `git worktree list --porcelain`, and scan task nodes
in relevant worktrees and local branch refs for owner lines. Stop if a rename
would invalidate another active task worktree or overlap user-owned task-tree
edits.

### Task 3: Migrate the Task Tree Atomically

**Files:**

- Modify: `docs/plans/TASK.md`
- Modify: `docs/plans/current-work/TASK.md`
- Modify: `docs/plans/upcoming-work/TASK.md`
- Rename/modify: all active task nodes below `docs/plans/current-work/`
- Rename/modify: all active task nodes below `docs/plans/upcoming-work/`
- Modify references: `docs/plans/**/*.md`
- Modify references: `docs/reviews/**/*.md`
- Modify references: `AGENTS.md`

**Step 1: Rename composite directories**

Use `git mv` so each composite task directory becomes `NN_slug/` and retains
its `TASK.md`.

**Step 2: Convert leaf directories to files**

Move each leaf `NN_slug/TASK.md` to its parent as `NN_slug.md`, then remove the
empty directory. Preserve file content and Git history.

**Step 3: Remove duplicated child indexes**

Delete `## Child Tasks` checklists and equivalent immediate-child lists from
composite `TASK.md` files. Keep aggregate scope, dependencies, status,
ownership, acceptance conditions, and explanatory ordering constraints that
are not redundant with numeric names.

**Step 4: Update canonical links**

Rewrite active plan, review, root-index, and repository-policy references to
the new paths. Do not add aliases, compatibility links, redirects, or support
for the previous paths.

**Step 5: Inspect the full migration diff**

Run `git status --short`, `git diff --stat`, `git diff --summary`, and
`git diff --check`. Confirm unrelated test edits remain unstaged and unchanged.

### Task 4: Update Task Selection and Execution Skills

**Files:**

- Modify: `.agents/skills/orion-task-runner/SKILL.md`
- Modify: `.agents/skills/orion-review-orchestrator/SKILL.md`

**Step 1: Define the task model once**

In `orion-task-runner`, define numbered composite directories, numbered leaf
Markdown files, unlimited nesting, local sparse numbering, unique sibling
prefixes, numeric selection order, planning insertion, claim storage, and
deletion on completion. Describe only the canonical model.

**Step 2: Separate selection from execution**

Make `orion-review-orchestrator` a required sub-skill for every leaf execution.
Keep status-only requests, triage, and task planning in the runner without
starting execution.

**Step 3: Require minimal implementation delta**

Require every implementation worker launched by the orchestrator to use
`minimal-implementation` before and during implementation, including its final
self-review and required change summary. Preserve the existing review gate,
worktree isolation, user integration gate, verification ownership, and cleanup
rules.

**Step 4: Remove obsolete assumptions**

Remove directory-only leaf identity, child-link ordering, checked-child state,
and completion wording that retains or deletes a leaf directory. Do not mention
migration or accept both layouts.

### Task 5: Verify the Canonical Model

**Files:**

- Validate: `.agents/skills/orion-task-runner/`
- Validate: `.agents/skills/orion-review-orchestrator/`
- Validate: `docs/plans/current-work/`
- Validate: `docs/plans/upcoming-work/`

**Step 1: Run skill validation**

Run:

```bash
python3 /Users/vi/work/.codex/deta/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/orion-task-runner
python3 /Users/vi/work/.codex/deta/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/orion-review-orchestrator
```

Expected: both report valid skills.

**Step 2: Check filesystem invariants**

Verify recursively that every task entry below the queue roots begins with a
numeric prefix, every composite contains `TASK.md`, leaf tasks are Markdown
files, sibling prefixes are unique, and no task-only leaf directory remains.

**Step 3: Check documentation links**

Run a repository-local Markdown link check over `AGENTS.md`, `.agents/`, and
`docs/`, or an equivalent deterministic script. Expected: every relative link
to an active task node resolves.

**Step 4: Re-run the pressure scenario**

Give a fresh independent agent the same hypothetical numbered tree and the
updated skills.

Expected: it selects the lowest-numbered ready leaf recursively, stores its
claim in that leaf file, delegates execution to `orion-review-orchestrator`,
requires the worker to apply `minimal-implementation`, and deletes the leaf file only in
the reviewed completion commit.

**Step 5: Review for architectural duplication**

Apply `minimal-implementation` in read-only review mode to the resulting task workflow.
Confirm there is one task identity, one ordering source, one claim location,
and one execution path.

### Task 6: Commit the Atomic Migration

**Files:**

- Commit only the task-tree, reference, and skill changes from Tasks 3-5.
- Leave unrelated working-tree changes unstaged.

**Step 1: Stage the migration**

Stage only `.agents/skills/orion-task-runner/SKILL.md`,
`.agents/skills/orion-review-orchestrator/SKILL.md`, `AGENTS.md`, and the
intended `docs/` changes.

**Step 2: Validate the staged diff**

Run `git diff --cached --check` and inspect `git diff --cached --stat` plus the
skill and task-tree portions of the staged patch.

**Step 3: Create one logical commit**

Run:

```bash
git commit -m "Adopt numbered filesystem task queues"
```

Expected: one documentation-only commit containing the canonical migration and
no unrelated source or test changes. Do not run Maven tests for this
documentation-only commit.

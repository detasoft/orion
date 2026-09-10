# Review Skill Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Add a repository skill that audits one or more modules into `MODULE_REVIEW.md` and then repairs the
resulting findings interactively through the repository execution workflows.

**Architecture:** Keep the workflow in one self-contained `.agents/skills/orion-review/SKILL.md`. Audit modules in
parallel with one `gpt-6-astra`/`xhigh` worker per module, validate and write evidence-backed reports, then consider
`orion-simple-workflow` first for each repair and choose the proportionate execution workflow. Require
`orion-minimal-implementation` when ranking and repairing every finding, and preserve the chosen workflow's gates.

**Tech stack:** Codex skills, Markdown, Orion task tree and execution workflows.

---

### Task 1: Define and validate the review workflow

**Files:**

- Create: `.agents/skills/orion-review/SKILL.md`

1. Record baseline behavior without the skill for multi-module audit and interactive repair scenarios.
2. Write the minimal skill covering module resolution, Astra/xhigh audit workers, stale-finding removal,
   evidence links, safe simplicity ordering, and execution-workflow selection.
3. Require `orion-minimal-implementation` for consumer, invariant, and contract checks before ranking or repair.
4. Validate the skill with the bundled skill validator and Markdown checks.
5. Run the same scenarios with the skill through independent subagents and close any demonstrated gaps.

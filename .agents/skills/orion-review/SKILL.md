---
name: orion-review
description: >-
  Use when auditing one or more Orion modules, maintaining MODULE_REVIEW.md, or
  repairing the active findings in those reports.
---

# Orion Review

Audit the requested modules against current repository evidence and keep each
`MODULE_REVIEW.md` as a list of active findings. A requested module audit
authorizes the resulting report commit, but never authorizes production repairs.

**REQUIRED SUB-SKILL:** Use `orion-minimal-implementation` for the audit,
ordering, and every repair.

## Resolve and inspect

Resolve each requested module to the directory containing its `pom.xml` or
other manifest. Put its report at `<module>/MODULE_REVIEW.md`.

Read `AGENTS.md`, `docs/reviews/RULES.md`,
`orion-minimal-implementation`, the existing report, relevant plans, and local
`@AiRule` comments. Record current `HEAD`, `git status --short`, worktrees,
task claims, and target-file diffs. Preserve unrelated work.

For several modules, launch one fresh read-only analysis worker per module, up
to available concurrency, using model `gpt-6-astra`, reasoning effort `xhigh`,
and no inherited conversation or the smallest supported bounded fork. Supply
only the module, report, applicable rules, and output contract.

Workers inspect production code, tests, history, documentation, configuration,
and real consumers across boundaries where needed. They return evidence and
proposed report changes without editing or committing files. The primary agent
validates every finding and cross-module conclusion.

## Evidence rules

Treat every existing finding as a hypothesis. Remove it and dependent prose when
its premise is fixed or disproved. Keep a partially unresolved finding only
after rewriting it to the remaining current problem. Git history, a missing
symbol, or an old date alone is not evidence of resolution.

Add findings only for current structural or behavioral problems supported by
real code and consumers. Apply `docs/reviews/RULES.md`; exclude style remarks,
speculative future needs, and unsupported redesign.

Apply the test-quality rules from `orion-minimal-implementation` explicitly.
Report tests that inspect source code, build files, or configuration files as
text instead of exercising observable behavior through supported interfaces.

For each finding, record:

1. **Problem** and a concrete trigger.
2. **Sources** linking production symbols, meaningful tests, callers, and owners.
3. **Documented behavior**, or state that none was found.
4. **Contract** distinguishing required behavior from incidental behavior.
5. **Minimal repair** after applying `orion-minimal-implementation`.
6. **Alternatives and consequences** that are real for the current contract.
7. **Confidence** with the principal uncertainty.
8. **Priority signals:** importance and repair ease, each with evidence.

Finding numbers are stable identifiers. Never renumber existing findings or
reuse gaps. Assign each new finding one above the highest number ever used in
that report, considering both its current contents and Git history (including
renames, deleted findings, and deletion or recreation of the report). Deleting
the highest finding or all findings does not reset the sequence. Recover missing
history before assigning a number when the historical maximum cannot be established.

Apart from a minimal title, the report contains active findings only. Do not add
status/date/count boilerplate, general summaries, progress notes, or resolved
history.

When no active findings remain, delete `MODULE_REVIEW.md` instead of keeping an
empty or title-only report. If an audit finds no issues and no report exists,
do not create one. Include the deletion in the same audit or verified repair
commit and update any links that would otherwise point to the deleted report.
Deleting the report does not reset its finding-number history.

## Commit the audit result

After validating all requested modules, update their reports and any references
affected by report deletion in the current worktree and branch.
Stage only those verified audit changes, inspect
the complete staged diff, and commit the coherent report update immediately with
a descriptive single-line subject. No additional commit approval or project
test is required. If nothing changed, create no commit.

Then present confirmed findings, removed stale findings, coverage limits, and the
ordered repair queue. Do not start a repair unless the user requested it.

## Repair queue

Order repairs by the smallest independently verifiable architectural delta,
subject to dependencies and blocking correctness or security:

1. delete an unused concept, duplicate path, unreachable branch, redundant
   state, or obsolete configuration;
2. narrow visibility or derive a value from its existing owner;
3. weaken an unneeded guarantee only after verifying consumers and containment;
4. correct local behavior through an existing mechanism;
5. change cross-module, lifecycle, concurrency, wire, or persisted contracts.

Use priority ratings as evidence, not a score. Before each repair, establish the
required result, owner and production path, real consumers, preserved invariants,
affected guarantee, and verification.

Select the execution workflow through the canonical definitions. Several
findings use Simple by default, one checkpoint at a time. A task-tree leaf or
isolated repair uses Change.

`MODULE_REVIEW.md` findings are never task claims. Do not create one task per
finding merely for routing. Change may create one task only when its own
mechanics require it.

The repair checkpoint or task commit must update or remove every finding it
resolves. Do not create a separate report lifecycle commit after the repair.
After each repair, rebuild the queue because one change may resolve or invalidate
several findings.

If a finding contains an unresolved behavior or contract choice, obtain that
decision before implementation. A finding card is evidence, not authorization
to choose a product contract.

## Completion

A review is current when every retained finding is supported by present code and
real consumers, resolved findings and dependent prose are gone, links resolve,
and uncertainty is explicit.

A repair run is complete only when every authorized finding is resolved or
proved inapplicable, every implementation reached its selected workflow's
completion condition, and each remaining report describes only active findings.

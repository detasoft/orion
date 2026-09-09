---
name: review
description: Use when reviewing one or more Orion modules, creating or refreshing MODULE_REVIEW.md, or repairing findings from those reports interactively.
---

# Module Review

Audit the requested modules against the current repository, keep each
`MODULE_REVIEW.md` current, and turn confirmed findings into an interactive
repair queue. Treat every existing finding as a hypothesis that must be checked
again before implementation.

**REQUIRED SUB-SKILLS:** Use `orion-minimal-implementation` for the audit, ordering,
and every repair. Use `orion-review-orchestrator` for the repair queue and
`orion-task-runner` for task-tree ownership.

## Resolve the modules

Accept one module name/path or several. Resolve each to the directory containing
its `pom.xml` or other module manifest. Ask only when a name matches multiple
modules and repository evidence cannot disambiguate it. Put the report at
`<module>/MODULE_REVIEW.md`; create it when absent.

Before delegation, read `AGENTS.md`, `docs/reviews/RULES.md`,
`orion-minimal-implementation`, the existing report, relevant plans, and local
`@AiRule` comments. Record HEAD, `git status --short`, worktrees, existing task
claims, and target-file diffs. Never absorb, discard, or overwrite unrelated
work.

## Audit modules in parallel

Launch one fresh analysis worker per module, up to available concurrency:

- model `gpt-6-astra`;
- reasoning effort `xhigh`;
- `fork_turns="none"`, or the smallest bounded fork supported;
- explicit module path, report path, repository rules, and output contract;
- permission to edit only that module's `MODULE_REVIEW.md`.

Analysis workers inspect production code, tests, history, documentation,
configuration, and real consumers across module boundaries where a contract or
ownership claim requires it. They do not edit source, tests, build files, task
state, or repository metadata, and do not run Maven merely for a static audit.
The primary agent reviews every report diff and cross-module conclusion.

For each old finding, verify its essential premise in the current production
path. Remove it when the problem is fixed or the premise is disproved. Also
remove its dependent bullets, migration steps, conceptual-model statements,
questions, and stale coverage claims. Do not retain resolved findings as a
history section; Git already preserves history. A rename, missing symbol, or old
date alone is insufficient evidence. Keep partially unresolved findings and
rewrite them to describe only the remaining problem.

Add new findings only when supported by concrete behavior and real consumers.
Apply the blocking criteria from `docs/reviews/RULES.md`, but do not inflate the
report with style remarks or speculative future needs.

## Report contract

Preserve a useful existing report structure. Each active finding must include:

1. **Problem** — the observable or structural failure and a concrete trigger.
2. **Sources** — relative Markdown links to production symbols, meaningful
   tests, callers, and owners. Include line anchors when practical.
3. **Documented behavior** — links to the specification, README, plan, or
   external authoritative documentation that defines the behavior. Say that no
   authoritative documentation was found when that is the result.
4. **Contract** — the exact public, wire, persisted, lifecycle, concurrency, or
   internal guarantee involved; distinguish a verified requirement from current
   incidental behavior.
5. **Minimal repair** — the smallest safe change after applying
   `orion-minimal-implementation`.
6. **Alternatives and consequences** — include only real alternatives, naming
   compatibility, newly possible failures, lost capability, and containment.
7. **Confidence** — high, medium, or low, with the main uncertainty.

For a broad review also keep scope and limits, the verified current model,
things that can be deleted and their preconditions, an incremental path,
invariants that must remain, and material open questions.

As each worker finishes, the primary agent validates its report and immediately
presents its confirmed findings with clickable source and documentation links,
contract, and repair options. After all workers finish, recheck cross-module
conclusions and finalize the overall repair queue. Also present removed stale
findings, important coverage limits, and a detailed card for the first repair.
Keep report findings ranked by structural value; order the separate repair
queue by the smallest safe change as described below.

## Order by the smallest safe repair

Order the implementation queue by the smallest independently verifiable
architectural delta. Compare the actual number of affected concepts, contracts,
modules, and failure modes; the verb `delete` or `weaken` does not make a broad
change small. Among otherwise comparable candidates, start in this order:

1. delete an unused concept, duplicate path, unreachable branch, redundant
   state, or obsolete configuration;
2. narrow visibility or derive a value from its existing owner;
3. weaken an unneeded contract or guarantee after verifying every real consumer
   and containing the newly possible failures;
4. make a local behavior correction using an existing mechanism;
5. perform cross-module, lifecycle, concurrency, wire, or persisted-contract
   changes.

Dependencies and the need to keep every checkpoint working constrain this
order. Report blocking correctness or security findings immediately even when
they are not the first repair. Never call contract weakening trivial merely
because it deletes code.

Before selecting any repair, `orion-minimal-implementation` must establish the
required result, current owner and production path, real consumers, preserved
invariants, and the exact guarantee affected. For contract weakening, also name
new failure modes, containment, compatibility impact, and the concrete
structure removed. If evidence shows the guarantee is required, preserve it and
choose the next-smallest repair.

## Interactive repair loop

When repair was requested, search all existing task nodes, including claimed
ones, before creating anything. A matching claimed node blocks duplicate work.
Map each authorized repair to a suitable existing unclaimed node when possible;
otherwise create the smallest coherent leaf needed to execute it, including a
small repair. Group related small findings when they form one verifiable result,
group changes that must be atomic, and separate independent outcomes. Follow the
repository rule to commit newly created task-tree state immediately without
tests. Do not create one task mechanically for every report bullet.

Before handing off each task, show a finding card containing:

- problem and concrete trigger;
- source files and symbols;
- documented behavior link, if any;
- contract to preserve or change;
- recommended minimal repair and viable alternatives;
- expected tests, consequences, and known uncertainty.

If the report leaves a material behavior or contract choice unresolved, or the
user asked to choose among alternatives, obtain that decision before worker
handoff. A finding card is evidence, not approval of an unresolved product
choice. Do not repeat approval for a repair whose design the user already
accepted.

Invoke `orion-review-orchestrator` with the current task as its pool. Its code
worker model and effort remain the values specified by that skill. The primary
agent remains coordinator and reviewer; it does not implement branch fixes.
Make report cleanup part of the task's definition of done: before final squash,
the implementation worker revalidates the finding, removes every resolved or
invalidated item and dependent prose from the affected `MODULE_REVIEW.md`, and
includes that documentation in the reviewed task commit.

Keep the orchestrator's mandatory reviewed-commit user gate. After the user
authorizes or confirms integration, complete tests and cleanup, then verify the
integrated report read-only. If broader revalidation is needed, use a fresh
Astra/xhigh analysis worker with the audit restrictions above and commit only
this workflow's report changes as documentation before continuing. Confirm a
clean `main`, rebuild the queue, and automatically present and start the next
ready task without asking the user to select it again.

Rebuild the queue after each repair because one implementation may resolve or
invalidate several findings. Before starting the next orchestrator instance,
remove obsolete unclaimed task nodes created by this workflow in a
documentation-only commit. Before deletion, update surviving task indexes,
dependency references, and active-plan outstanding-work entries with verified
resolution evidence as required by the current `orion-task-runner`; preserve
unrelated and claimed task state. Stop only for the orchestrator gate, another
applicable explicit design gate, an unresolved product decision, a claimed
dependency, unsafe unrelated workspace state, or a genuine implementation
blocker. Continue independent audits while waiting for a decision when
possible.

If only an audit was requested, update the reports and stop after reporting the
results; do not start task or implementation work. If remediation requires a
clean committed `main`, commit only the hunks created by this workflow in
authorized review and plan documents needed as orchestrator inputs.
Authorization to update a report does not authorize pre-existing changes in the
same file. Never stage unrelated changes. If ownership cannot be separated or
unrelated state still prevents a clean base, report the exact blocker rather
than stashing or deleting it.

## Completion

A module review is current when every retained finding is supported by the
present code and its real consumers, resolved findings and dependent prose are
gone, report links resolve, and uncertainties are explicit. The repair pool is
complete only when every finding is either resolved by a verified integrated
change or proved no longer applicable, every executed task has passed
orchestrator review, integration verification and cleanup, no required task
remains, and the affected `MODULE_REVIEW.md` files contain no resolved finding.

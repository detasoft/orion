---
name: orion-review
description: >-
  Use when reviewing one or more Orion modules, creating or refreshing
  MODULE_REVIEW.md, or repairing findings from those reports interactively.
---

# Orion Review

Audit the requested modules against the current repository, keep each
`MODULE_REVIEW.md` current, and turn confirmed findings into an interactive
repair queue. Treat every existing finding as a hypothesis that must be checked
again before implementation.

**REQUIRED SUB-SKILLS:** Use `orion-minimal-implementation` for the audit, ordering,
and every repair. Use `orion-change-orchestrator` only for repairs that do not
qualify for the direct small-repair path below, and use `orion-task-runner` when
task-tree ownership applies.

`MODULE_REVIEW.md` files belong to the repository
[workflow-control scope](../../../docs/definitions.md#workflow-control-scope).
Apply its shared rules: edit and commit reports directly on `main`, never through
the orchestrator, coordinator, implementation worker, dedicated worktree, or
subagent. Record review state as early as it becomes accurate: commit newly
confirmed issues before starting their repairs, and immediately after a fix
lands on `main`, revalidate the affected issue and commit its removal or update
in a separate documentation-only commit. Do not defer report maintenance to a
later repair or batch when it can already be stated correctly.

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
- read-only access: return evidence and proposed report changes without editing
  `MODULE_REVIEW.md` or any repository file.

Analysis workers inspect production code, tests, history, documentation,
configuration, and real consumers across module boundaries where a contract or
ownership claim requires it. They do not edit source, tests, build files, task
state, or repository metadata, and do not run Maven merely for a static audit.
The primary agent reviews every proposed report change and cross-module
conclusion.

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

Apart from a minimal title, keep `MODULE_REVIEW.md` as a list of active issues
only. Do not add status lines, review or re-evaluation dates, issue counts,
general summaries, introductory prose, progress notes, or resolved-history
sections. In particular, do not write boilerplate such as `Status: re-evaluated
against current code on <date>; <count> structural findings remain`. Add an issue
when confirmed, update it when its present facts change, and delete it when
resolved or disproved.

Each active issue must include:

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
8. **Priority signals** — importance and repair ease, each rated high, medium,
   or low with a short evidence-based reason. Importance reflects present impact
   and affected behavior; repair ease reflects the smallest safe architectural
   delta and required verification, not raw line count. Use unknown and name the
   missing evidence when either rating cannot yet be supported.

Treat finding numbers as stable identifiers. Never renumber existing findings
when adding, updating, removing, or reordering them. Number each new finding one
higher than the maximum finding number currently present; do not reuse gaps.

As each worker finishes, the primary agent validates its evidence and
cross-module conclusions, updates only the corresponding `MODULE_REVIEW.md`
directly on `main`, and commits those report changes before starting a repair.
Stage only report hunks produced by this workflow; never absorb pre-existing
changes in the same file. Then immediately present the confirmed findings with
clickable source and documentation links, contract, and repair options. After
all workers finish, recheck cross-module conclusions and finalize the overall
repair queue. Also present removed stale findings, important coverage limits,
and a detailed card for the first repair. Keep report findings ranked by
structural value and importance, using repair ease to distinguish otherwise
comparable findings. Order the separate repair queue by the smallest safe change
as described below.

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

Use the recorded importance and repair-ease ratings as prioritization evidence,
not as a mechanical score. A high-importance finding remains visible even when
its repair is hard; an easy low-impact cleanup must not hide or delay a blocking
issue.

Before selecting any repair, `orion-minimal-implementation` must establish the
required result, current owner and production path, real consumers, preserved
invariants, and the exact guarantee affected. For contract weakening, also name
new failure modes, containment, compatibility impact, and the concrete
structure removed. If evidence shows the guarantee is required, preserve it and
choose the next-smallest repair.

## Review Unit Boundaries

Treat one module audit and one authorized repair as separate bounded review
units. An analysis worker belongs to one module audit only. After the primary
agent validates its result and commits the corresponding report state, do not
message or reuse that worker for another module, a later refresh, or repair
revalidation. Any later analysis uses a fresh worker with no inherited turns.

A repair unit remains active through its implementation, review fixes,
integration, cleanup, and the separate revalidation commit for
`MODULE_REVIEW.md`. Once those steps are complete, apply the orchestrator's
completed-task boundary and retire every subagent assigned to that repair. A
direct small repair has the same context boundary even though it uses no worker.

Before leaving either completed unit, show the user what was reviewed or solved,
how the result was established, which parts and report entries changed, actual
verification and coverage limits, remaining risks or work, and concrete next
steps. Then reconstruct the next review context from current `HEAD`, workspace
state, active task-tree nodes, applicable rules and plans, unresolved user
decisions, and a fresh read of the relevant `MODULE_REVIEW.md`. Its active
findings are the canonical repair queue. Do not carry a resolved finding's
investigation, rejected alternatives, worker conversation, or deleted report
text into the next unit. Preserve a cross-finding fact only when it is still
supported by current repository evidence, a retained active finding, or an
unresolved decision. Use native context compaction when available; otherwise
the reconstructed working set is the context reset.

## Interactive repair loop

When repair was requested, first classify each authorized repair. It qualifies
for the direct small-repair path only when it is local to one existing module and
concept, has a narrow and mechanically clear result, and changes no public,
wire, persisted, lifecycle, concurrency, cross-module, dependency, build, or
configuration contract. Verify real consumers before classifying a deletion as
small; the verb `delete` alone does not qualify a repair.

For a qualifying small repair, the primary agent may edit, test, review, and
commit directly on `main` without `orion-change-orchestrator`, an implementation
worker, a subagent, a branch, or a worktree. Do not create a task merely to route
such a repair. Still apply `orion-minimal-implementation`, preserve unrelated
workspace state, add or update tests when behavior changes, and run verification
proportional to the change. Immediately revalidate and commit the corresponding
report update separately after the repair lands.

For every other repair, search all existing task nodes, including claimed ones,
before creating anything. A matching claimed node blocks duplicate work. Map
each authorized repair to a suitable existing unclaimed node when possible;
otherwise create the smallest coherent leaf needed to execute it. Group related
small findings when they form one verifiable result, group changes that must be
atomic, and separate independent outcomes. Follow the repository rule to commit
newly created task-tree state immediately without tests. Do not create one task
mechanically for every report bullet.

Before handing off each task, show a finding card containing:

- problem and concrete trigger;
- source files and symbols;
- documented behavior link, if any;
- contract to preserve or change;
- recommended minimal repair and viable alternatives;
- importance, repair ease, and the evidence for both;
- expected tests, consequences, and known uncertainty.

If the report leaves a material behavior or contract choice unresolved, or the
user asked to choose among alternatives, obtain that decision before worker
handoff. A finding card is evidence, not approval of an unresolved product
choice. Do not repeat approval for a repair whose design the user already
accepted.

For a repair outside the direct small-repair path, invoke
`orion-change-orchestrator` with the current task as its pool. Its code worker
model and effort remain the values specified by that skill. The primary agent
remains coordinator and reviewer; it does not implement branch fixes. The
implementation worker never edits `MODULE_REVIEW.md`. After the reviewed fix
lands on `main`, the primary agent immediately revalidates the issue, removes or
updates every resolved or invalidated item and dependent prose, and commits only
that report change directly on `main` as documentation-only work.

For orchestrated repairs, keep the orchestrator's mandatory reviewed-commit user
gate. After the user authorizes or confirms integration, complete tests and
cleanup, then update and commit the integrated report directly on `main` before
continuing. If broader revalidation is needed, use a fresh Astra/xhigh analysis
worker with the read-only audit restrictions above; the primary agent still
makes and commits the report change. Confirm that unrelated state is preserved,
rebuild the queue, and automatically present and start the next ready task
without asking the user to select it again.

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

If only an audit was requested, update and commit each report directly on `main`
as soon as its validated result is ready, then stop after reporting the results;
do not start task or implementation work. If remediation requires a clean
committed `main`, commit only the hunks created by this workflow in authorized
review and plan documents needed as orchestrator inputs.
Authorization to update a report does not authorize pre-existing changes in the
same file. Never stage unrelated changes. If ownership cannot be separated or
unrelated state still prevents a clean base, report the exact blocker rather
than stashing or deleting it.

## Completion

A module review is current when every retained finding is supported by the
present code and its real consumers, resolved findings and dependent prose are
gone, report links resolve, and uncertainties are explicit. The repair pool is
complete only when every finding is either resolved by a verified integrated
change or proved no longer applicable, every direct repair has passed its
required verification, every orchestrated task has passed review, integration
verification and cleanup, no required task remains, and the affected
`MODULE_REVIEW.md` files contain no resolved finding.

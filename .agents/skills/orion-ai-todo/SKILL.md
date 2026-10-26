---
name: orion-ai-todo
description: >-
  Use when asked to find, assess, or resolve AI_TODO comments in Orion source code.
---

# Orion AI TODO

Treat each `AI_TODO` comment as a proposal to investigate, not an instruction
to implement. Resolve it with the user through
[orion-simple-workflow](../orion-simple-workflow/SKILL.md), applying
[orion-minimal-implementation](../orion-minimal-implementation/SKILL.md).

These annotations are normally temporary prompts for the current development
session. Commit the agreed result without its `AI_TODO` comment. Keep questions
and the remaining queue in session context instead of adding TODO comments as
a substitute for discussion. Exclude pending, newly added session annotations
from the checkpoint's staged diff; preserve unowned working-tree annotations
for discussion rather than committing them or silently deleting them. Existing
markers outside the agreed checkpoint do not authorize unrelated cleanup.

A search or assessment-only request is read-only: report the evidence and
recommendations without starting an execution workflow or editing comments.
Use the resolution steps below when the user requests resolving the items.

## Find the queue

Read `AGENTS.md`, the two skills above, and applicable local rules. Inspect
`git status --short` and target-file diffs before changing anything. Preserve
unrelated staged and unstaged work.

Search the user-selected scope, or repository source code when none is given:

```sh
rg -n --hidden --sort path -g '!.git' -g '!**/target/**' -g '!**/node_modules/**' 'AI_TODO' .
```

Classify matches by their actual context. Include source comments and their
continuation lines; exclude string literals, test fixture data, generated output,
and documentation that merely describes the marker, including this skill.
An `AI_TODO` annotation applies to the next line of code after its comment,
skipping blank lines and the comment's continuation lines. Use that line as
the target of the proposal; read surrounding code to understand its role and
the consequences of changing it.
Read each complete comment and its enclosing declaration rather than treating
the matched line as the whole request. Follow applicable class-level `@AiRule`
comments.

Present a short inventory with locations and the requested changes. Follow the
user's order; otherwise use path and line order, explaining dependency-driven changes
before proceeding. Closely dependent comments may form one coherent checkpoint,
but explain that grouping in its proposal. Do not create a plan document,
module report, or task-tree entries merely to record this queue.

If there are no actionable comment matches, report that result without edits
or a commit.

## Assess one proposal

Before proposing a checkpoint, trace the affected production path, real callers,
tests, and documented contracts. Check the comment against current behavior:

- What concrete problem would the proposed change solve?
- Why does the current structure exist, and which consumers rely on it?
- Which behavior and guarantees must survive, including state ownership,
  concurrency, lifecycle, error handling, and persisted or wire contracts?
- Can the existing owner or mechanism express the change more simply?
- Is the comment current, already satisfied, contradicted by evidence, or
  dependent on a user decision?

For example, a request to remove a forwarding thread also requires checking
where backpressure moves and whether publishers hold locks needed by handlers.
A comment's confidence or imperative wording does not establish correctness.

Choose and explain one disposition:

| Evidence | Proposed checkpoint |
| --- | --- |
| The requested result is justified | Make the smallest coherent behavior change and remove the resolved comment. |
| The result is already implemented, stale, or unjustified | Remove only the obsolete TODO text, with supporting evidence. |
| The proposed mechanism is unsuitable, but the problem is real | Discuss a smaller or safer alternative before implementation. |
| A requirement or tradeoff remains unresolved | Discuss it with the user and obtain a concrete decision before editing. |

Do not equate rejecting the proposed mechanism with disproving the underlying
problem. Do not offer leaving the TODO as a resolution: discuss uncertainties,
objections, and alternatives until the user agrees on a behavior change or
removal of the comment. A request to process the queue does not settle product
decisions and does not authorize silently deleting unresolved requests.

## Resolve through Simple

Use Simple as the execution workflow for both comment-only removal and code
changes. Follow its checkpoint cycle rather than treating the inventory as
blanket implementation or commit approval.

Present the evidence, recommendation, affected paths, preserved guarantees,
verification, and remaining queue. Obtain agreement on this concrete checkpoint
before editing. Explain whether it removes only the comment or also changes
behavior, and resolve material contract questions before implementing them.

Remove the marker and its obsolete explanatory text only when the agreed result
makes it obsolete. Preserve useful neighboring comments. If a comment contains
several requests, discuss all of them and agree on their disposition before
removing it. If a proposed repair cannot be completed safely, explain the blocker
and discuss a feasible alternative or rejection of the proposal with the user.
Pending discussion is unfinished work, not a completed item with a retained TODO.

Use the canonical verification table for the actual changed files, including
source files with comment-only edits. Add meaningful tests when behavior changes;
do not invent behavior tests solely for deleting an obsolete comment.
Automatically review the result, then stage only this checkpoint and present
its diff and verification for user review as Simple requires. Do not commit
without approval of that result or a covering explicit commit instruction.

After committing and compacting context, refresh the queue and immediately
propose its next checkpoint. Do not edit a later checkpoint while the current
one remains unfinished. At completion, report the agreed code changes and
comment removals, verification, and unrelated workspace state. Distinguish
searching the selected scope from auditing the whole repository.

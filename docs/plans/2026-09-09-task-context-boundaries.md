# Task Context Boundaries Implementation Plan

**Goal:** Make task completion a hard context boundary for normal Orion task
execution and `orion-review` repair loops.

## 1. Capture the current failure mode

Run a read-only pressure scenario against the current skills. Verify whether the
agent is explicitly required to emit a completion handoff, retire completed-task
subagents, and rebuild the next context from current repository state.

## 2. Define the generic task boundary

Update `orion-change-orchestrator` so completed work produces the required
user-facing summary, completed-task workers are never reused, and continuing
pools begin from a reconstructed minimal context rather than accumulated task
reasoning.

## 3. Define review context reconstruction

Update `orion-review` so audit and repair subagents are bounded to one unit, a
completed repair is summarized before reset, and the next repair context is
rebuilt from the current `MODULE_REVIEW.md` and repository state after report
maintenance.

Update `orion-task-runner` only if its selection rules need an explicit guarantee
that deleted/completed leaf details are not carried into the next selection.

## 4. Verify the skills

Run the same pressure scenario against the updated skills with a fresh evaluator.
Confirm that it chooses a fresh subagent, carries only current durable evidence,
and emits the required completion output. Validate each changed skill with the
skill validator and inspect the final diff for contradictions or duplicated
ownership.

## 5. Commit workflow control

Commit only the intended repository-skill changes directly on `main` as
documentation-only workflow-control work. Do not run Maven tests.

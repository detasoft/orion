# Repository definitions

## Terms

- A **checkpoint** is one coherent, complete, reviewable change that leaves the
  repository working and can be committed independently.
- The **current worktree and branch** are the worktree and checked-out branch in
  which Quick or Simple was started. Those workflows do not switch branches.
- An **explicit commit instruction** is a user instruction such as `commit`,
  `сделай коммит`, or an equivalent unambiguous request covering the result being
  prepared. It authorizes that commit after all required checks and reviews.
- A **task claim** is an `Owner:` entry in the body of one executable task-tree
  leaf. `Status:`, prose, parent composites, and `MODULE_REVIEW.md` findings are
  never claims.

## Repository workflows

Read-only review, explanation, planning, and status requests do not select an
execution workflow and do not authorize repository changes. `orion-review` is
the narrow exception: a requested module audit updates and commits its report,
but does not authorize repairs.

Honor an explicitly requested workflow when its mechanics can complete the
request safely. Otherwise choose by the shape of the work, not by file type:

| Work | Workflow |
| --- | --- |
| One understood, coherent result and one commit | Quick |
| Several sequential checkpoints, or a requested staged user review | Simple |
| Execution of a task-tree leaf, or work requiring an isolated worker | Change |

Every workflow may update task status and completion state through
`orion-task-runner`. Recording the verified result of Quick or Simple in the
task tree does not require Change. Completion must satisfy the workflow that
performed the work; Change's integration and cleanup gates apply only to work
executed through Change.

Escalate to a workflow with stronger mechanics when inspection reveals that the
selected one cannot safely contain the work. Do not silently carry partial edits
into another workflow: preserve and report the current state first.

### Commit authorization

- A mutating request performed through Quick authorizes its one logical commit.
- Simple presents every staged checkpoint for user review. An explicit commit
  instruction covering that checkpoint means the review is complete: commit it
  immediately without requesting the same approval again.
- Change prepares and reviews its task commit. An explicit commit instruction in
  the request also authorizes immediate integration after the review and required
  checks succeed. Without it, stop with the reviewed commit and request
  integration approval.

Authorization never includes unrelated files, later scope expansion, or a
different checkpoint.

### Staging boundary

Keep a result unstaged until required verification and automatic review have
passed. Staging is the handoff of a ready result for user feedback or immediate
commit, never a place to wait for running checks. After approval, commit
immediately. Quick and other already-authorized commits stage and commit without
an additional waiting step. If feedback requires corrections, unstage only the
affected result, correct and reverify it, then review and stage it again.
Preserve unrelated index entries throughout. This boundary concerns the current
result's checks; it does not reserve the index or block other sessions from
staging and committing their own work in parallel.

## Pre-commit verification

Determine verification from the files in the checkpoint, independently of the
selected workflow. Run every required check before creating the commit and
against the content intended for that commit. If content changes afterward,
rerun the affected check. A failed required check blocks the commit.

| Changed content | Required pre-commit verification |
| --- | --- |
| Documentation only | No automated check required; inspect the diff. |
| Maven/JVM source or build input | `mvn test -Pdev -T 4` from the repository root. |
| Rust source or build input under `session-host/` | `make session-host-test`. |
| Both Maven/JVM and `session-host/` source or build inputs | `make test`. |
| `Makefile` or `*.mk` | Run each affected real goal. |
| Any other file | The executor chooses a useful proportionate check, or reports that none applies. |

Documentation includes `*.md`, task descriptions, and review reports.
Maven/JVM input includes Java, tests, `pom.xml`, module resources, schemas,
ANTLR, and Maven-managed frontend files. For any affected Make goal that is
destructive, interactive, dependent on unavailable external state, or otherwise
unsafe to run, use `make -n <goal>` and report the limitation.

For a checkpoint matching several rows, run the union of their checks; one
broader command may satisfy narrower rows when it actually executes them. Tests
must run outside the sandbox because they may need loopback sockets. Do not
repeat an identical passed check after commit unless the committed or integrated
environment itself is what the check must validate.

Use `make run-test MODULE=<module> TEST='<test-locator>'` for focused Maven tests
during development. Focused tests do not replace the table's full pre-commit
check. Use `make test` for routine verification after Change integration.

### Quick workflow

Quick produces one coherent commit in the current worktree and branch. A result
may require several internal steps, but they are not separate checkpoints or
commits.

1. Inspect repository state and preserve unrelated changes.
2. Make only the requested coherent change.
3. Run required verification and automatically review the complete result.
4. Stage only its verified files and hunks, then inspect the staged diff.
5. Commit immediately with a descriptive single-line subject.

Quick does not launch a worker, create a worktree, or add a staged user-review
gate. It may update task-tree state in scope through `orion-task-runner` without
starting task-tree implementation. If the request contains several independently
committable results, use Simple rather than splitting them inside Quick.

### Simple workflow

Simple implements one or more checkpoints sequentially in the current worktree
and branch. Only one checkpoint may be unfinished at a time.

For each checkpoint:

1. Implement the complete checkpoint while preserving unrelated work.
2. Run its required pre-commit verification.
3. Perform the automatic review required by repository rules.
4. Stage the ready result and present it with verification to the user. Treat an explicit
   commit instruction covering it as completed user review.
5. Commit immediately using `<stable task name>: <imperative checkpoint
   summary>`.
6. Compact context before starting the next checkpoint.

Context retained after compaction consists of the original request or review,
the remaining checkpoint queue, still-applicable user decisions, current
`HEAD` and workspace state, and for each completed checkpoint its commit SHA,
short result, and `command -> passed/failed` verification summary. Remove raw
tool and test output, detailed investigation, local decisions, rejected
alternatives, and discussion that cannot affect remaining work.

Simple has no dedicated worker or worktree. It may update task status and
completion state in scope through `orion-task-runner`, including in the same
checkpoint as the verified result. When repairing a `MODULE_REVIEW.md` finding, update or remove
that finding in the same checkpoint commit as the verified repair.

### Change workflow

Change executes one task-tree leaf through a dedicated branch and worktree, one
fresh implementation worker, primary-agent review, integration, and cleanup.

Before worker launch, the primary agent creates or selects one executable leaf,
records its claim only in that leaf, and commits the claim on `main`. The worker
implements the explicit deliverables, runs required pre-commit verification,
and prepares one logical task commit. The primary agent reviews the complete
result and returns findings to the same worker.

After a clean review, integrate immediately when an explicit commit instruction
already authorizes it; otherwise request approval. Integrate with cherry-pick,
run `make test` on `main`, then remove the completed worktree and branch. Only
after verified integration and cleanup may the primary agent delete the task
leaf and eligible empty ancestors and commit that completion state.

Primary-owned claim, pause, governing-input, and completion commits are direct
state commits made at the moment their statements become true. They do not
start another workflow or become part of the worker's implementation commit.

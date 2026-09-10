# Agent Instructions

## Routing and ownership

- For change requests, select Quick, Simple, or Change from
  [the repository workflow definitions](docs/definitions.md#repository-workflows).
  An explicitly requested workflow is binding while it remains safe and
  sufficient for the work.
  Read-only review, explanation, planning, and status requests do not select an
  execution workflow; requested module-report updates use `orion-review`.
- Use `orion-task-runner` for every task-tree operation. Executing a task-tree
  leaf uses Change workflow; planning, status, and task-tree edits alone do not
  start or claim implementation.
  Any workflow may update task status and completion state through that skill
  when the update is supported by verified work within the request's scope.
- Use `orion-minimal-implementation` for every implementation or review.
  Review, status, and explanation requests are read-only except that
  `orion-review` maintains and commits requested module reports.
- Preserve unrelated staged and unstaged changes. Never stage, discard, or
  commit work not produced by the current request.
- Execution plans contain requirements, design, dependencies, and result
  verification. Do not put skill or execution-workflow references, staging,
  commit, review-gate, or branch-integration recipes in plans. These instructions
  belong in `AGENTS.md`, `docs/definitions.md`, and repository skills.

## Commits and verification

- Minimize time in the index in every workflow. Finish required verification
  and automatic review before staging the result for user feedback or commit.
  Do not stage this result while its checks are still running. After approval, commit immediately;
  if approval is already covered, stage and commit without an extra waiting step.
  If feedback requires corrections, unstage only this result, make the corrections,
  verify and review again, then restage. Preserve unrelated index entries throughout.
  This does not reserve the shared index: other sessions may stage and commit
  their own changes in parallel. Never include or unstage their entries.
- Follow the canonical
  [pre-commit verification table](docs/definitions.md#pre-commit-verification).
  Every required check must pass before the corresponding commit.
- An explicit `commit`, `сделай коммит`, or equivalent instruction means the
  user review for the covered result is complete. Commit it immediately after
  required verification and automatic review; do not request the same approval
  again.
- Commit messages are one line without a body. Preserve the subject formats
  required by the selected workflow.
- If the worktree contains unrelated changes, stage only the files and hunks
  owned by the current checkpoint. Keep one coherent result in one commit;
  genuinely unrelated results require separate checkpoints and commits.
- If a required check fails and cannot be fixed in scope, do not commit. Report
  the command and relevant failure. Do not debug failures caused by unrelated
  working-tree changes unless requested.
- If a required post-commit check fails and the failure is fixed, create the fix
  commit with exactly the same subject as the original commit.

## Test commands

- Use `make help` as the entry point for repository commands and prefer a
  documented Make goal. Run tools directly only when no suitable goal exists or
  a more specific instruction requires the exact command.
- Run all tests outside the sandbox because they may need loopback sockets.
- Use `mvn test -Pdev -T 4` for the full Maven/JVM pre-commit check.
- Use `make session-host-test` for the Rust `session-host` pre-commit check.
- Use `make test` when both Maven/JVM and `session-host` must be checked and
  for routine Change-workflow verification after integration.
- For focused Maven tests, always use
  `make run-test MODULE=<module> TEST='<test-locator>'`. The target supplies the
  dev profile, reactor dependencies, parallelism, and Surefire configuration.
- Run `mvn verify -Pdev -T 4` when the Maven verify lifecycle is explicitly
  needed. Run Maven without `-Pdev` only to check default-build behavior or
  integration tests.
- Do not run integration tests automatically after every commit.
- When requesting approval for a Maven command, put the phase immediately after
  `mvn`, then pass the remaining arguments.

## Git integration

- Do not use `git merge` or create merge commits when integrating upstream
  branches. Rebase unless the user explicitly requests a merge commit.
- Change workflow transfers its reviewed task commit to `main` with
  `git cherry-pick`. Do not report completion until required verification
  passes and the dedicated worktree and task branch are removed.

## Implementation rules

- Add or extend tests whenever observable behavior changes, regardless of the
  selected workflow. Cover the straightforward path and at least one meaningful
  non-trivial scenario chosen from the actual risks.
- For `Continuation` implementations, write production continuation logic
  before its tests. This exception changes test order, not the requirement for
  coverage.
- In `Output` implementations, report expected serialization, validation, and
  delivery failures through the standard result/flow interface, such as
  `SendResult.Failed`; do not throw exceptions as expected control flow.
- When replacing an internal API, behavior, or concept, update every real
  in-repository consumer and remove the old production path in the same change.
  Do not retain compatibility shims, deprecated aliases, dual paths, migration
  modes, or feature flags for the old internal model.
- Preserve meaningful behavior coverage through the replacement API. Remove
  tests whose only purpose is asserting absence of the old behavior in a
  separate commit.
- Prefer ordinary loops and straightforward control flow over Java streams
  unless streams are noticeably clearer.
- Keep source lines at or below 112 characters; up to 135 is acceptable only
  for a line that barely cannot fit.
- Mark test-only non-contract methods with
  `core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/TestOnly.java`.
- When comments or explanations are requested for classes, add class-level
  comments only unless method or constructor comments are explicitly requested.
- Treat class-level `@AiRule` comments as local implementation rules. Read and
  revalidate them whenever changing that class.
- For code review, apply [the blocking review rules](docs/reviews/RULES.md).
  Never approve a blocking violation.

## Minimal implementation policy

Implement the smallest change that makes the requested behavior true while
preserving existing behavior and architectural invariants. Reuse existing
concepts before adding state, APIs, services, dependencies, configuration, or
abstractions. Prefer deletion and one canonical production path. Do not perform
unrelated refactoring or preserve an old internal path for hypothetical
consumers. Apply the full analysis and final summary from
`orion-minimal-implementation`.

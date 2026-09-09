# Agent Instructions

- When the user asks to commit changes, for example by writing `commit` or `сделай коммит`, create the intended logical commit first, then run regular Maven tests for the whole project with `make test`.
- Do not run tests after documentation-only commits, including commits that
  change only Markdown files such as task `TASK.md` files and files under
  `docs/`.
- Do not commit changes you did not make in the current requested work unless the user explicitly asks to commit those specific changes. If unrelated or pre-existing changes are present, leave them unstaged and report them separately.
- Use `make test` for routine full-project tests and the commit workflow.
- For focused Maven tests, always use
  `make run-test MODULE=<module> TEST='<test-locator>'`. The target supplies the
  `dev` profile, reactor dependencies, parallelism, and the Surefire setting
  needed for helper modules without the selected test.
- Do not run integration tests automatically after every commit; `make test` is enough for the commit workflow.
- Use `mvn verify -Pdev -T 4` for routine development verification. Run Maven without `-Pdev` only when explicitly checking the default build behavior or integration tests.
- The project allows running `mvn verify` from the repository root without asking for additional confirmation when it is explicitly needed.
- The project allows running `make test`, `make run-test` with any module and
  test locator, and `mvn test` with any Maven parameters without asking for
  additional confirmation.
- Always run test commands outside the sandbox, because local tests may need to bind loopback sockets and sandboxed runs can fail with `Operation not permitted`.
- When requesting approval for Maven commands, put the Maven phase immediately after `mvn`, then pass the remaining arguments, for example `mvn test -q -pl ...`.
- After committing, run `make test`. If it fails and the failure is fixed, create the follow-up fix commit with the exact same commit message as the original commit so the commits can be squashed later.
- If the Maven test command fails and cannot be fixed in the current turn, report the failure and the relevant error output.
- If post-commit Maven tests fail because of unrelated or pre-existing working tree changes, do not debug those changes unless the user explicitly asks; report the failure and finish the requested commit task.
- If the working tree contains multiple unrelated or clearly separate changes, split them into separate commits. Stage only the files that belong to each commit.
- Do not use `git merge` or create merge commits when integrating `origin/main` or other upstream branches. Use `git rebase` instead, unless the user explicitly asks for a merge commit.
- When finishing a requested change in a dedicated Git worktree:
  - After implementation, review fixes, and verification are complete, squash
    all commits unique to the task branch into one logical commit.
  - For a direct change without a queued task, use a descriptive single-line
    subject. Do not create a task, claim, task tag, or completion-deletion target
    merely to execute the change.
  - For queued task execution, use the squashed commit subject template:
    `<imperative summary> [task: <leaf-path-relative-to-its-queue-root>]`.
    Example:
    `Implement native protocol bootstrap [task: 05_native-session-host/01_contracts-and-build.md]`.
  - For queued work, the implementation worker retains the leaf and claim in
    its squash. The primary `orion-change-orchestrator` coordinator owns completion cleanup
    in the dedicated task worktree and amends that same commit, preserving its
    subject. This permits only completion metadata edits, never implementation
    code or tests; present the amended SHA at the integration gate.
  - For queued work, the coordinator deletes the completed numbered leaf file and removes
    completed empty composite ancestor directories in full only when their
    aggregate acceptance and scope are satisfied. Preserve parents with
    unfinished siblings, the queue roots, and root `TASK.md`. It removes the task's
    outstanding-work entries from active plans and replaces still-needed
    dependency references with verified completion evidence. Do not retain
    completed task nodes or renumber remaining siblings to close gaps.
  - Transfer the squashed commit to `main` with `git cherry-pick`, never with a
    merge commit. Run the required post-commit tests on `main`, then remove the
    completed worktree and its branch only after confirming the transfer and a
    clean worktree.
  - Do not report the task complete until `git worktree list` no longer shows
    the completed worktree and its task branch has been deleted.
- When adding or changing functionality, add or extend tests in the same change. Cover the straightforward happy path and at least one meaningful non-trivial scenario, such as overwrite/update behavior, missing or invalid state, reloads, multiple backends, or other edge cases relevant to the feature.
- For implementations of `Continuation`, write the production continuation
  logic first and add or update its tests afterward. Do not use test-first TDD
  for `Continuation` classes. This exception does not remove the requirement to
  cover continuation behavior with tests in the same change.
- In `Output` implementations, report expected serialization, validation, and
  delivery failures through the standard output result/flow interface, such as
  `SendResult.Failed` and its continuation transition. Do not use `throw new`
  exceptions as expected `Output` control flow.
- When replacing one behavior or concept with another, do not add or keep tests whose only purpose is to assert that the previous behavior is absent. Remove those legacy negative checks in a separate commit after the behavior-change commit.
- When replacing an internal API, behavior, or concept, update every real
  in-repository consumer and delete the old path in the same task. Do not
  introduce or retain deprecated aliases, adapters, compatibility shims, dual
  read/write paths, migration modes, or feature flags for the old model. Tests
  are not consumers: preserve meaningful behavior coverage through the single
  replacement API and remove legacy-only coverage under the separate-commit
  rule above.
- Prefer a model in which each operation has one canonical production path. If
  code, state, configuration, coordination, or an API can be removed without
  losing required runtime behavior or an explicitly preserved wire/persisted
  contract, removing it is the highest implementation and task-selection
  priority. Hypothetical future or external consumers are not a reason to
  preserve an old internal API.
- Prefer ordinary loops and straightforward control flow over Java Stream API unless streams make the code noticeably more readable.
- Keep source-code lines at or below 112 characters. In exceptional cases where
  a line barely does not fit, up to 135 characters is acceptable.
- If a method is created only for use in tests and is not part of the public contract, mark it with `core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/TestOnly.java`.
- When asked to add comments or explanations to classes, add class-level comments only. Do not add method or constructor comments unless explicitly requested.
- Treat class-level comments tagged with `@AiRule` as local implementation rules. When changing a class, read these comments and verify the rules still hold before finishing the change.
- For code reviews, read and apply the blocking criteria in
  [`docs/reviews/RULES.md`](docs/reviews/RULES.md). Do not approve a change that
  violates a blocking review rule.
- Commit messages must be a single line. Do not add a body, bullet points, or multi-line descriptions — the entire meaning goes in the subject line.
- Use the filesystem task tree rooted at `docs/plans/TASK.md` to track current
  high-level implementation work and upcoming tasks. `current-work/` and
  `upcoming-work/` are unnumbered queue roots. Below them, `NN_slug/` directories
  describe composite tasks in `TASK.md`, and `NN_slug.md` files are executable
  leaves. Nest composites to any depth. Numeric prefixes are local, may have
  gaps, and must be unique across sibling files and directories. Filesystem
  order is the only child queue; do not duplicate it in parent checklists.
  `TASKS.md` is only a compatibility pointer; do not maintain task lists there.
  Keep task nodes short, update them when starting or finishing substantial
  work, and leave detailed designs and implementation steps in ordinary
  `docs/plans/` plan files.
- Use `orion-task-runner` directly for creating or editing tasks, task
  descriptions, ordering, composition, and dependencies, and for task selection.
  Make documentation-only changes, all `MODULE_REVIEW.md` changes, and skill
  edits directly on `main`: do not route them through
  `orion-change-orchestrator`, a coordinator, an implementation worker, a
  dedicated worktree, or a subagent. This exception does not change the
  task-runner and queued-execution ownership rules for task claims and completion
  metadata. Use `orion-change-orchestrator` for requested source, build, and
  configuration changes, whether direct or queued. If one request mixes those
  changes with documentation or skill edits, keep the documentation and skill
  portion on `main` and orchestrate only the implementation portion.
  Review/status-only requests remain read-only. The implementation worker must
  apply `orion-minimal-implementation`. Plan insertion follows the intended local
  numeric order; queued execution selects the first unclaimed, dependency-ready
  leaf.
- Whenever you create a task, commit its task-tree changes immediately without
  waiting for a separate commit request. Treat this as a documentation-only
  commit and do not run tests afterward.


# Repository Agent Policy

## Core implementation policy

- Make the smallest change that satisfies the requirement.
- Preserve all externally observable behavior not explicitly changed by the task.
- Preserve existing architectural invariants and module boundaries.
- Reuse existing concepts, abstractions, protocols, lifecycle, state, and configuration before introducing new ones.
- New abstractions, public APIs, persistent state, protocols, dependencies, configuration options, modules, and services have a cost and require concrete justification from the current requirement.
- Do not perform unrelated refactoring.
- Extensibility is not a goal unless explicitly required.
- Prefer local changes over cross-module changes when both are correct.
- Prefer fewer changed files, fewer changed types, and fewer new concepts over broader cleanup.
- Prefer simplification or deletion over addition when behavior remains equivalent.
- Do not introduce a general-purpose abstraction for a single use case unless the existing code cannot express the requirement cleanly.

## Decision order

When several implementations satisfy the requirement, choose in this order:

1. Preserve behavior not mentioned by the task.
2. Preserve architectural invariants.
3. Minimize public contract changes.
4. Minimize affected modules.
5. Minimize changed files.
6. Minimize new types/interfaces/classes.
7. Minimize new configuration and persistent state.
8. Minimize dependencies.
9. Minimize code added.

## Architectural-change trigger

Always use the `orion-minimal-implementation` skill when reviewing changes or
developing tasks, regardless of the scope or complexity of the work. Apply its
implementation workflow for changes and its read-only mode for review requests.

Before making a change that introduces or materially modifies any of the
following, apply the skill's model, concept, and contract analysis:

- public API or protocol
- persistent state or schema
- cross-module behavior
- lifecycle or ownership
- concurrency model
- new dependency
- new service/module/subsystem
- new reusable abstraction

A new `Manager`, `Provider`, `Registry`, `Factory`, `Coordinator`, `Service`, or similar concept should be treated as an architectural change unless it is clearly an implementation detail local to one existing concept.

## Review expectation

After any non-trivial implementation, perform the self-review required by
`orion-minimal-implementation`: verify that the same requirement could not be met
with fewer concepts or a smaller architectural delta. For cross-module,
concept-heavy changes, inspect both individual modules and their interactions.

Finish implementation with the skill's required summary: what was being
solved, how it was solved, which parts changed and what changed in each, and
verification results with any remaining work.

## Philosophy

The central rule is:

> Implement the smallest change that makes the requested behavior true while preserving existing invariants. Introduce no new concepts unless the requirement cannot be satisfied with concepts already present in the codebase.

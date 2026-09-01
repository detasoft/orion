# Agent Instructions

- When the user asks to commit changes, for example by writing `commit` or `сделай коммит`, create the intended logical commit first, then run regular Maven tests for the whole project with `make test`.
- Do not run tests after documentation-only commits, including commits that
  change only Markdown files such as task `TASK.md` files and files under
  `docs/`.
- Do not commit changes you did not make in the current requested work unless the user explicitly asks to commit those specific changes. If unrelated or pre-existing changes are present, leave them unstaged and report them separately.
- Use `make test` for routine full-project tests and the commit workflow. For focused checks, use the `dev` Maven profile, for example `mvn test -Pdev -T 4 -q -pl ...`.
- When running focused Maven tests for a module that needs reactor dependencies,
  include `-am`; when also passing `-Dtest=...`, include
  `-Dsurefire.failIfNoSpecifiedTests=false` so helper modules without the
  selected test do not fail the build.
- Do not run integration tests automatically after every commit; `make test` is enough for the commit workflow.
- Use `mvn verify -Pdev -T 4` for routine development verification. Run Maven without `-Pdev` only when explicitly checking the default build behavior or integration tests.
- The project allows running `mvn verify` from the repository root without asking for additional confirmation when it is explicitly needed.
- The project allows running `make test` and `mvn test` with any Maven parameters without asking for additional confirmation.
- Always run test commands outside the sandbox, because local tests may need to bind loopback sockets and sandboxed runs can fail with `Operation not permitted`.
- When requesting approval for Maven commands, put the Maven phase immediately after `mvn`, then pass the remaining arguments, for example `mvn test -q -pl ...`.
- After committing, run `make test`. If it fails and the failure is fixed, create the follow-up fix commit with the exact same commit message as the original commit so the commits can be squashed later.
- If the Maven test command fails and cannot be fixed in the current turn, report the failure and the relevant error output.
- If post-commit Maven tests fail because of unrelated or pre-existing working tree changes, do not debug those changes unless the user explicitly asks; report the failure and finish the requested commit task.
- If the working tree contains multiple unrelated or clearly separate changes, split them into separate commits. Stage only the files that belong to each commit.
- Do not use `git merge` or create merge commits when integrating `origin/main` or other upstream branches. Use `git rebase` instead, unless the user explicitly asks for a merge commit.
- When finishing task work in a dedicated Git worktree:
  - After implementation, review fixes, and verification are complete, squash
    all commits unique to the task branch into one logical commit.
  - Use the squashed commit subject template:
    `<imperative summary> [task: <path-from-the-task-queue-without-TASK.md>]`.
    Example:
    `Implement native session host protocol bootstrap [task: native-session-host/contracts-and-build]`.
  - Delete the completed leaf task directory and remove its link from the
    parent `TASK.md` in the squashed commit instead of retaining a completed
    task node.
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
  high-level implementation work and upcoming tasks. Every task directory must
  contain its own `TASK.md`, and directories may form a hierarchy of tasks.
  `TASKS.md` is only a compatibility pointer; do not maintain task lists there.
  Keep task nodes short, update them when starting or finishing substantial
  work, and leave detailed designs and implementation steps in ordinary
  `docs/plans/` plan files.

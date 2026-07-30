# Agent Instructions

- When the user asks to commit changes, for example by writing `commit` or `сделай коммит`, create the intended logical commit first, then run regular Maven tests for the whole project with `make test`.
- Do not run tests after documentation-only commits, including commits that
  change only Markdown files such as `TASKS.md` and files under `docs/`.
- Do not commit changes you did not make in the current requested work unless the user explicitly asks to commit those specific changes. If unrelated or pre-existing changes are present, leave them unstaged and report them separately.
- Use `make test` for routine full-project tests and the commit workflow. For focused checks, use the `dev` Maven profile, for example `mvn test -Pdev -T 4 -q -pl ...`.
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
- If a method is created only for use in tests and is not part of the public contract, mark it with `core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/TestOnly.java`.
- When asked to add comments or explanations to classes, add class-level comments only. Do not add method or constructor comments unless explicitly requested.
- Treat class-level comments tagged with `@AiRule` as local implementation rules. When changing a class, read these comments and verify the rules still hold before finishing the change.
- Commit messages must be a single line. Do not add a body, bullet points, or multi-line descriptions — the entire meaning goes in the subject line.
- Use `TASKS.md` to track only current high-level implementation work and a small set of upcoming high-level tasks. The current section may contain multiple active tasks, not just one. Keep it short, update it when starting or finishing substantial work, and leave detailed designs and implementation steps in `docs/plans/`.

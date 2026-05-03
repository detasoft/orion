# Agent Instructions

- When the user asks to commit changes, for example by writing `commit` or `сделай коммит`, create the intended logical commit first, then run regular Maven tests for the whole project.
- Use the `dev` Maven profile for routine local checks and the commit workflow, for example `mvn test -Pdev` or `mvn test -Pdev -q -pl ...`.
- Do not run integration tests automatically after every commit; `mvn test -Pdev` is enough for the commit workflow.
- Use `mvn verify -Pdev` for routine development verification. Run Maven without `-Pdev` only when explicitly checking the default build behavior or integration tests.
- The project allows running `mvn verify` from the repository root without asking for additional confirmation when it is explicitly needed.
- The project allows running `mvn test` with any Maven parameters without asking for additional confirmation.
- When requesting approval for Maven commands, put the Maven phase immediately after `mvn`, then pass the remaining arguments, for example `mvn test -q -pl ...`.
- After committing, run the Maven test command. If it fails and the failure is fixed, create the follow-up fix commit with the exact same commit message as the original commit so the commits can be squashed later.
- If the Maven test command fails and cannot be fixed in the current turn, report the failure and the relevant error output.
- If the working tree contains multiple unrelated or clearly separate changes, split them into separate commits. Stage only the files that belong to each commit.
- Prefer ordinary loops and straightforward control flow over Java Stream API unless streams make the code noticeably more readable.
- When asked to add comments or explanations to classes, add class-level comments only. Do not add method or constructor comments unless explicitly requested.

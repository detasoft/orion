# Agent Instructions

- When the user asks to commit changes, for example by writing `commit` or `сделай коммит`, run `mvn verify` for the whole project from the repository root before committing.
- The project allows running `mvn verify` from the repository root without asking for additional confirmation.
- The project allows running `mvn test` with any Maven parameters without asking for additional confirmation.
- When requesting approval for Maven commands, put the Maven phase immediately after `mvn`, then pass the remaining arguments, for example `mvn test -q -pl ...`.
- If `mvn verify` fails, do not commit. Report the failure and the relevant error output.
- If `mvn verify` passes, stage only the intended changes and create a Git commit with a meaningful message that describes the change.
- If the working tree contains multiple unrelated or clearly separate changes, split them into separate commits. Stage only the files that belong to each commit.

# Agent Instructions

- When the user asks to commit changes, for example by writing `commit` or `сделай коммит`, create the intended logical commit first, then run the Maven verification command requested by the workflow.
- The project allows running `mvn verify` from the repository root without asking for additional confirmation.
- The project allows running `mvn test` with any Maven parameters without asking for additional confirmation.
- When requesting approval for Maven commands, put the Maven phase immediately after `mvn`, then pass the remaining arguments, for example `mvn test -q -pl ...`.
- After committing, run the Maven verification command. If it fails and the failure is fixed, create the follow-up fix commit with the exact same commit message as the original commit so the commits can be squashed later.
- If the Maven verification command fails and cannot be fixed in the current turn, report the failure and the relevant error output.
- If the working tree contains multiple unrelated or clearly separate changes, split them into separate commits. Stage only the files that belong to each commit.

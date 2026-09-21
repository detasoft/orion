# Module Review: `tests/git-engine-test-support`

## 1. The former transcript harness has no callers

**Problem and evidence.** [Scenarios](src/main/java/pro/deta/orion/git/Scenarios.java) is a 1083-line collection
of transcript constants, JGit server adapters, fixture creation and scenario entrypoints. Repository-wide
searches find no caller outside the class itself. The 40-line
[BaseOrionTest](src/main/java/pro/deta/orion/git/BaseOrionTest.java) has no subclass or other consumer.
Neither is registered through reflection or resources; neither contributes a runnable test on its own.

**Required contract.** Live behavioral and interoperability coverage remains required. The current
[GitWorkflowScenarios](src/main/java/pro/deta/orion/git/workflow/GitWorkflowScenarios.java), harness,
[GitInteroperabilityMatrixRunner](src/main/java/pro/deta/orion/git/workflow/GitInteroperabilityMatrixRunner.java),
Orion adapters and parser/transport tests provide the exercised paths. No requirement or current caller
supports the abandoned transcript helper API.

**Minimal repair and tests.** Delete `Scenarios.java` and `BaseOrionTest.java`. No running test needs
migration to remove these unused helpers. Keep matrix test classes: several have no direct references or
locally declared test methods because JUnit executes inherited tests from the matrix runner.

**Alternatives and consequences.** Reintroducing calls to the old transcripts is not needed for removing
dead support code. Deletion removes 1123 lines without reducing currently executed coverage. It does not
claim that every historical transcript scenario has equivalent current coverage; future coverage work
should exercise the live protocol API.

**Confidence and priority.** High for repository references and JUnit reachability; external test-library
consumers were not established. Medium maintenance importance and high repair ease.

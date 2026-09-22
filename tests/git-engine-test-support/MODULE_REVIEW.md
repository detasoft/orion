# Module Review: `tests/git-engine-test-support`

## 1. The former transcript harness has no callers

**Problem and evidence.** [Scenarios](src/main/java/pro/deta/orion/git/Scenarios.java) is a 1083-line collection
of transcript constants, JGit server adapters, fixture creation and scenario entrypoints. Repository-wide
searches find no caller outside the class itself. The 40-line
[BaseOrionTest](src/main/java/pro/deta/orion/git/BaseOrionTest.java) has no subclass or other consumer.
Neither is registered through reflection or resources; neither contributes a runnable test on its own.

**Required contract.** Preserve useful scenarios by migrating them to the current
[GitWorkflowScenarios](src/main/java/pro/deta/orion/git/workflow/GitWorkflowScenarios.java), harness,
[GitInteroperabilityMatrixRunner](src/main/java/pro/deta/orion/git/workflow/GitInteroperabilityMatrixRunner.java),
Orion adapters and parser/transport tests. Assertions must check operation results, refs, history and file
content rather than reproducing JGit's exact progress messages, capability ordering and compressed pack bytes.
Keep the specialized matrices for
[protocol v2 discovery and fetch](../git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/FetchProtocolInteroperabilityTest.java),
[branch fetch grants](../git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/BranchFetchInteroperabilityTest.java),
[atomic ref creation and updates](../git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/AtomicPushInteroperabilityTest.java)
and [HTTP push without discovery](../git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/PushDiscoveryInteroperabilityTest.java).

**Minimal repair and tests.** Remove the unused helpers while preserving the existing matrix and focused
parser coverage, including malformed fetch/push rejection and unchanged refs in
[FetchCommandPackTest](../../git/git-parser/src/test/java/pro/deta/orion/git/parser/v2/command/FetchCommandPackTest.java)
and [PushCommandTest](../../git/git-parser/src/test/java/pro/deta/orion/git/parser/v2/command/PushCommandTest.java).
Keep matrix test classes: several have no direct references or locally declared test methods because JUnit
executes inherited tests from the matrix runner.

**Alternatives and consequences.** Directly reconnecting the old runner would enforce incidental byte
representations and retain a second test infrastructure. Delete only the unused helpers; the active behavior
tests are required coverage and must remain runnable.

**Confidence and priority.** High for repository references and JUnit reachability; external test-library
consumers were not established. Medium maintenance importance and high repair ease.

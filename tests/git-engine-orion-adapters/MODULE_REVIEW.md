# Module Review: `tests/git-engine-orion-adapters`

## 1. HTTP interoperability tests duplicate the shared Git process runner

**Problem and evidence.**
[HTTP test git helper](src/test/java/pro/deta/orion/transport/http/OrionGitHttpInteroperabilityTest.java#L183)
reimplements command assembly, environment isolation, temporary logs, timeouts and forced termination.
The existing dependency supplies
[GitCommandRunner](../git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitCommandRunner.java#L50),
already used by shallow, server-option and partial-clone tests.
The HTTP helper ignores its final termination wait result and retains two logs per command until directory
cleanup. Process-management and isolation changes must be maintained twice.

**Contract.** Keep all seven parameter rows, explicit v1/v2, consecutive negotiation, the divergent history,
request-count assertions, exact file output, unchanged local state and fsck.
Its instrumented HTTP servlet observes behavior unavailable from the generic server fixture.

**Minimal repair and validation.** Move the test into `pro.deta.orion.git.workflow` and use the existing
package-private runner with a 30-second timeout; retain only protocol/negotiation argument construction.
No HTTP package-private API requires its current package. Run the same seven rows after relocation.
Preserve stdout assertions: the common runner merges diagnostic streams, so successful content-query commands
must remain unambiguous under its controlled environment.

**Alternatives and consequences.** Do not add a public runner or generic test framework, or replace the
instrumented servlet. Preserve the common runner's bounded process cleanup and caller interruption contract,
covered by [GitCommandRunnerTest](../git-engine-test-support/src/test/java/pro/deta/orion/git/workflow/GitCommandRunnerTest.java).

**Confidence and priority.** High from both live helpers and existing runner consumers.
P2 duplicated test infrastructure; shared-runner cleanup is fixed, so consolidation can preserve the distinct scenarios.

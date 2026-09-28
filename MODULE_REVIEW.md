# Module Review: `orion`

## 8. Root inheritance exports JUnit as a production dependency

**Problem.** Root `junit-jupiter-api` and `junit-jupiter-params` dependencies have default compile scope.
Ordinary production Maven artifacts transitively supply JUnit even when their production sources do not use it.

**Sources.** [Inherited dependencies](pom.xml#L237), [example production module](agent-protocol/pom.xml),
and [distribution exclusions](core/bootstrap/pom.xml#L108).
The [matrix runner](tests/git-engine-test-support/src/main/java/pro/deta/orion/git/workflow/GitInteroperabilityMatrixRunner.java#L3)
legitimately uses API and params in main sources; its
[matrix consumer](tests/git-engine-interoperability-matrix/src/test/java/pro/deta/orion/git/workflow/matrix/OrionFacingGitMatrixTest.java)
depends on that contract. [Tests parent](tests/pom.xml#L34) already declares compile-scoped API.

**Documented behavior and contract.** [README](README.md#development) documents the test lifecycle, without
requiring every production artifact to export JUnit. Published test-support code must retain compile dependencies
used by its main-source runner.

**Minimal repair.** Scope the two root dependencies to test, preserve test-parent compile API, and declare
compile params directly in `tests/git-engine-test-support`. Verify the reactor and effective dependency graph.

**Alternatives and consequences.** Shading exclusions only fix runnable distributions. Removing compile JUnit
from test support breaks its real runner. Narrow ownership removes unused production dependencies while
preserving tests and test artifacts. Do not assert POM structure with a test.

**Confidence.** High from actual imports and consumers; the changed effective graph has not been built.

**Priority signals.** Importance: medium due to unnecessary coupling across modules. Repair ease: high,
a few build-scope changes with complete reactor verification.

## 10. HTTP clone helper generates an endpoint rejected by the transport

**Problem.** The documented `make clone-http-repo project` sends Git to `/r/project`, producing discovery at
`/r/project/info/refs`. The current HTTP route requires the explicit `.git/` boundary and returns HTTP 400.

**Sources.** [Helper example and URL](make/server.mk#L107),
[route validation](net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L102),
and [behavior test](net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java#L401).

**Documented behavior and contract.** [README](README.md#L801) requires `.git` in HTTP remote URLs.
The helper promises cloning a named repository with an ephemeral bearer header.

**Minimal repair.** Normalize bare, suffixed, and namespaced arguments to exactly one final `.git` while
preserving bearer-header delivery. Test actual Make invocation through controlled SSH/Git commands.

**Alternatives and consequences.** Requiring users to supply the suffix narrows the documented helper.
Weakening HTTP path boundaries violates the existing tested transport contract. A local helper correction
adds no production abstraction.

**Confidence.** High from both endpoint implementations and their regression test; no live clone was run.

**Priority signals.** Importance: medium, an advertised command fails. Repair ease: high locally; affected
real goals require a server or documented dry-run limitation.

## 11. Diagnostic Make goals expose active administrator tokens

**Problem.** HTTP and SSH Git check goals print the acquired administrator bearer token. Admin ACL helpers
use `curl -v`, exposing the Authorization request header in stderr. Saved diagnostic output contains a usable
authentication secret; the SSH check does not use the token it obtains.

**Sources.** [HTTP diagnostic output](make/server.mk#L133),
[SSH diagnostic output](make/server.mk#L156), and [verbose ACL helpers](make/server.mk#L120).
`check-git-all` invokes the checks. [README](README.md#L204) recommends the ACL helper.

**Documented behavior and contract.** [Explicit token export](README.md#L212) is the purpose of
`issue-token` and `issue-token-raw`. Diagnostic goals require authenticated requests and useful results,
without a documented promise to publish the credentials used for them.

**Minimal repair.** Delete diagnostic token printing, remove unused token issuance from the SSH check, and
remove verbose request-header output from ACL helpers. Preserve intended token-export commands and useful
HTTP error/body reporting. Exercise success and failure via controlled helper commands.

**Alternatives and consequences.** Filtering verbose output adds redaction machinery and can miss secrets.
Removing the unnecessary output is smaller. Authentication and diagnostic results remain supported.

**Confidence.** High; exposure follows directly from the commands, without a live token invocation.

**Priority signals.** Importance: high when diagnostic output is saved or shared. Repair ease: high locally,
with observable output and error-path coverage required.

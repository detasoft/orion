# Module Review: `orion`

## 10. HTTP clone helper generates an endpoint rejected by the transport

**Problem.** The documented `make clone-http-repo project` sends Git to `/r/project`, producing discovery at
`/r/project/info/refs`. The current HTTP route requires the explicit `.git/` boundary and returns HTTP 400.

**Sources.** [Helper example and URL](make/server.mk#L114),
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

# Module Review: net/http-core

## 5. Git failure handling ignores response commitment

**Problem.** Git-specific failure handlers attempt sendError for authorization, encoding and
missing-repository failures without checking whether protocol streaming has already committed.
Once output is committed, the handler cannot replace it with an HTTP error status.

**Sources.** [Git catches](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java),
[servlet exception mapping](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteServlet.java),
[current streaming tests](src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java),
and [servlet routing tests](src/test/java/pro/deta/orion/transport/http/OrionHttpRouteServletRoutingTest.java).
The native route recorder models sendError as a status assignment rather than real response commitment.
The servlet protects committed responses for unexpected argument/state failures, but the Git handler's
local error paths do not use that protection.

**Documented behavior and contract.** Existing HTTP behavior distinguishes malformed requests, denied
access, missing repositories and server failures. Committed streams cannot receive a replacement status.
**Minimal repair.** Respect commitment in the Git failure path and terminate an already started stream
through its existing mechanism. Verify errors before and after real response commitment.
**Alternatives and consequences.** Buffering the entire response to defer commitment would undermine
streaming. A generic HTTP error framework is unnecessary; local commitment checks can preserve streaming.
**Confidence.** The commitment risk is supported by static control flow, without a Jetty reproduction.
Existing committed-state-error coverage is not claimed absent.
**Priority signals.** Importance high because failure handling after streamed output is ambiguous;
repair ease medium because tests must exercise actual response commitment.

## 13. Unknown smart HTTP discovery services return 400 instead of 403

**Problem.** An authenticated request to `/r/team/project.git/info/refs?service=git-unknown` returns 400.
Unsupported service selection is collapsed into the same null value as missing service input.

**Sources.** [discovery dispatch](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L132),
[serviceParameter](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L272),
[service names](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/exchange/InitialRequestService.java),
and [HTTP route tests](src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java).
No unsupported-service response assertion was found in the inspected tests.

**Documented behavior and contract.** Git's
[Smart Server Response specification](https://github.com/git/git/blob/master/Documentation/gitprotocol-http.adoc#smart-server-response)
requires 403 for an unrecognized or disabled service. The reference
[select_service implementation](https://github.com/git/git/blob/master/http-backend.c#L253-L277) follows that rule.
**Minimal repair.** Distinguish a supplied unsupported service from missing/malformed input in the existing
discovery dispatch; return 403 for the unsupported case. Add observable route coverage.
**Alternatives and consequences.** Changing every null service to 403 also changes missing-parameter
behavior unnecessarily. No new public abstraction is needed.
**Confidence.** High: current response and upstream contract are explicit.
**Priority signals.** Importance medium, a service-selection wire violation outside normal fetch/push;
repair ease high, local dispatch logic.

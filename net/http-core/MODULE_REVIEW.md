# Module Review: net/http-core

## 5. Backend argument failures become client errors and Git error handling ignores commitment

**Problem.** An authorized Git request opening an uncached repository with corrupt persisted metadata
can return HTTP 400 containing the backend exception message. Invalid/noncanonical stored names and
malformed Properties Unicode escapes throw IllegalArgumentException, which is treated as bad client input.
Git-specific failure handlers also attempt sendError without checking whether streaming has committed.

**Sources.** [Git catches](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L180),
[servlet exception mapping](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteServlet.java#L41),
[repository-service boundary](../git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java#L53),
[file metadata loading](../../git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/FileNativeGitRepositoryProvider.java),
[current streaming tests](src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java),
and [servlet routing tests](src/test/java/pro/deta/orion/transport/http/OrionHttpRouteServletRoutingTest.java).
The native route recorder models sendError as a status assignment rather than real response commitment.
The servlet has a committed-response check for IllegalStateException, but not for every relevant Git error.

**Documented behavior and contract.** Existing HTTP behavior distinguishes malformed requests, denied
access, missing repositories and server failures. Corrupt backend state is not malformed client input.
Unexpected failures require sanitized diagnostics; committed streams cannot receive a replacement status.
**Minimal repair.** Handle known client validation at its entry boundary, preserve repository failure
meaning, and stop mapping unexpected backend argument failures to 400. Respect commitment in the Git
failure path. Verify corrupt metadata and errors before/after real response commitment.
**Alternatives and consequences.** Globally mapping all argument failures to 500 would misclassify
legitimate malformed input; a generic HTTP error framework is unnecessary. The pack handler erases provider
failure codes too, but inspected current openForRead producers return NOT_FOUND, so a live non-absence
result becoming 404 is not established by this audit.
**Confidence.** High on corrupt metadata becoming 400; commitment risk is supported by static control flow,
without a Jetty reproduction. Existing committed-state-error coverage is not claimed absent.
**Priority signals.** Importance high for failure classification and incidental message exposure;
repair ease medium/low because validation ownership crosses service/storage boundaries.

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

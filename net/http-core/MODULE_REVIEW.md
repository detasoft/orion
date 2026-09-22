# Module Review: net/http-core

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

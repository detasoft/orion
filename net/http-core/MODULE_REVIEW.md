# Module Review: net/http-core

## 3. Route selection discards path structure and handlers interpret it again

**Problem.** The registry selects handlers with unrestricted character wildcards, then handlers independently
parse the same path. /session-hostile is captured by /session-host* and rejected only inside the download
handler. Git and pack routes overlap and depend on non-asterisk character count for precedence.

**Sources.** [Wildcard matching](src/main/java/pro/deta/orion/transport/http/WildcardMatcher.java#L16),
[registry selection](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteRegistry.java#L27),
[pattern ordering](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteRegistry.java#L63),
[session-host parsing](src/main/java/pro/deta/orion/transport/http/SessionHostDownloadRoute.java#L96),
[Git parsing](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L220),
[pack parsing](src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java#L154),
[root mounting](src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java#L128), and
[current synthetic overlap coverage](src/test/java/pro/deta/orion/transport/http/OrionHttpRouteServletRoutingTest.java#L39).

**Documented behavior.** The
[typed-matching task](../../docs/plans/tasks/12_http-core-hardening/02_typed-route-matching.md)
requires segment boundaries, shared captures and explicit ownership of /r/.

**Contract.** Preserve deterministic exact-route precedence, nested repository paths, the current frontend
fallback and feature authorization. Production currently mounts at /; differing context-path fallbacks are
not evidence of a currently configured non-root deployment failure.

**Minimal repair.** Normalize the adapter path once and return the matched route with its parsed remainder or
captures. Express current exact paths, segment-bound prefixes and fallback directly. Give /r/ one registered
owner and remove its overlapping wildcard definitions and redundant handler-local path recovery.

**Alternatives and consequences.** Narrowing /session-host* alone fixes that capture but leaves duplicated
Git parsing and wildcard precedence. Adding a general template language exceeds current requirements.
Changing /session-hostile selection need not change its final 404 response. Preserve decoding and traversal
rejection at one boundary, and test the production route set rather than inferring behavior from synthetic
patterns alone.

**Confidence.** High on duplicated interpretation and current prefix capture.

**Priority signals.** Importance: medium, because the current overmatch is concrete but the demonstrated
/session-hostile case still ends in 404. Repair ease: medium-to-low: unified invocation already exists, but
replacement must preserve overlapping routes, fallback and authorization behavior across several handlers.

## 5. HTTP failure classification loses domain meaning and response commitment

**Problem.** The servlet converts every IllegalStateException into HTTP 400 with its message, including
unexpected handler state failures. The pack route collapses every provider Result.Failure into absence,
and the gzip wrapper labels underlying transport I/O failures as invalid gzip. Streaming handlers attempt
HTTP error translation without checking whether protocol output is already committed.

**Sources.** [Servlet exception mapping](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteServlet.java#L28),
[repository-service failure conversion](../git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java#L94),
[Git catches](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L109),
[message scan](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L334),
[pack result conversion](src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java#L99),
[gzip conversion](src/main/java/pro/deta/orion/transport/http/GitHttpRequestBody.java#L24), and
[current gzip tests](src/test/java/pro/deta/orion/transport/http/GitHttpRequestBodyTest.java#L49).

**Documented behavior.** The integrated Smart HTTP and gzip implementations distinguish malformed requests,
denied access, missing repositories, failures after streaming, and broad source I/O wrapping. Narrowing that
classification changes the behavior established by `1818c028` and the current tests.

**Contract.** Malformed client input, absence, authorization failure and backend failure must remain distinct.
An unexpected internal failure must not expose incidental exception text as a client error. Setting status 200
alone does not commit a response; writing or flushing may. After commitment there can be no replacement HTTP
status. Existing 403 behavior for missing or invalid Bearer credentials is separately covered and need not
change with this repair.

**Minimal repair.** Translate expected validation and typed provider failures at their owning boundary.
Preserve absence information through the Git service boundary, remove message matching, and let unexpected
exceptions produce a sanitized server error. Before commitment translate failures normally; afterward
terminate the stream through its existing mechanism. Distinguish gzip syntax failures from source I/O failure
without buffering the request.

**Alternatives and consequences.** A universal HTTP error framework is unnecessary; local typed outcomes and
existing result codes can express these distinctions. Keeping the generic 400 catch misclassifies backend
faults. Changing 400 to 500 globally without identifying current validation throws would misclassify legitimate
client errors. New tests must exercise missing repositories, corrupt/backend state and failures before and
after actual response commitment.

**Confidence.** High on the inspected servlet, pack and gzip mappings. Git parser/storage internals were
excluded from this audit; no current claim is made about their persisted-metadata failure paths. The exact
typed replacement must follow the owning service boundaries.

**Priority signals.** Importance: high, because unexpected handler-state failures can be exposed as client
errors with incidental messages, and streaming failure behavior is ambiguous after commitment. Repair ease:
low, because absence and typed failures cross Git service, storage and HTTP boundaries and need pre- and
post-commit verification.

## 12. Token issuance buffers an unbounded body before authenticating the caller

**Problem.** POST /api/admin/token with any syntactically valid Basic credentials reads the entire request
body before checking the password. A nonexistent user can therefore cause allocation proportional to an
arbitrarily large, including chunked, body before JSON validation or authentication rejects the request.

**Sources.** [Public route and authentication order](src/main/java/pro/deta/orion/transport/http/OrionAdminIssueTokenRoute.java#L33),
[unbounded read](src/main/java/pro/deta/orion/transport/http/OrionAdminIssueTokenRoute.java#L79),
[servlet installation](src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java#L128),
[real token client](../../tests/integration-test/src/integration-test/java/pro/deta/orion/test/TestBearerTokens.java#L33),
[runtime acceptance tests](../../tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpAdminApiIT.java#L307),
and [existing bounded command-body read](src/main/java/pro/deta/orion/transport/http/SessionCommandsRoute.java#L63).

**Documented behavior.** The [token plan](../../docs/plans/tasks/14_application-tokens/04_oauth-authentication.md#L13)
describes Basic-to-Bearer issuance. The request contains one optional TTL; an empty body defaults to 900 seconds.
No large-body requirement or aggregate token-request limit was found.

**Contract.** Preserve Basic authentication, empty/default and explicit TTL requests, validation and ordinary
401 responses. Request processing must use a finite body bound; exceeding it should produce 413.

**Minimal repair.** Reuse the command route's readNBytes(limit + 1) pattern with a small fixed token-body limit,
rejecting overflow before JSON parsing and token issuance. Exercise empty/normal bodies and oversized input
without Content-Length, checking bounded consumption and absence of token issuance.

**Alternatives and consequences.** Content-Length checks alone miss chunked input. Streaming directly to Jackson
does not establish an aggregate byte limit. Preliminary authentication adds an unnecessary service interaction
and leaves authenticated input unbounded. A route-local cap needs no new configuration, service or API;
only oversized requests change behavior.

**Confidence.** High from the production call order; no load test was run. External proxy limits were not
established and do not bound Orion's directly exposed connector.

**Priority signals.** Importance: high, because valid credentials are unnecessary and request size controls
heap consumption. Repair ease: high, because the existing bounded-read mechanism is independently testable.

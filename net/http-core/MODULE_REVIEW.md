# Module Review: net/http-core

## 3. Route selection discards path structure and handlers interpret it again

**Problem.** The registry selects handlers by path patterns, then handlers independently parse the same
path. Git and pack routes overlap and depend on non-asterisk character count for precedence. Matching
does not carry parsed repository or operation information into handler invocation.

**Sources.** [Shared path matching](../../core/authorization/src/main/java/pro/deta/orion/auth/check/MatcherUtils.java),
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

**Alternatives and consequences.** Segment-aware matching already bounds the download prefix and
distinguishes immediate children from descendants, but leaves duplicated Git parsing and wildcard
precedence. Extending the pattern language does not remove that duplication. Preserve decoding and
traversal rejection at one boundary, and test the production route set rather than inferring behavior
from synthetic patterns alone.

**Confidence.** High on duplicated interpretation and overlapping Git route ownership.

**Priority signals.** Importance: medium, because path parsing and route ownership are duplicated across
the registry and handlers. Repair ease: medium-to-low: unified invocation already exists, but
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

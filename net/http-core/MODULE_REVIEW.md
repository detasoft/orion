# Module Review: net/http-core

## 5. HTTP failure classification loses domain meaning and response commitment

**Problem.** The servlet still converts every IllegalArgumentException into HTTP 400 with its message,
including internal contract violations such as buffered metadata passed to openResponseBody.
The pack route collapses every provider Result.Failure into absence,
and streaming handlers attempt HTTP error translation without checking whether protocol output is
already committed.

**Sources.** [Servlet exception mapping](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteServlet.java#L28),
[streaming metadata contract](src/main/java/pro/deta/orion/transport/http/OrionHttpExchange.java),
[repository-service failure conversion](../git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java),
[Git catches](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java),
[message scan](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java),
[pack result conversion](src/main/java/pro/deta/orion/transport/http/OrionGitPackfileHandler.java),
[current streaming tests](src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java).

**Documented behavior.** The integrated Smart HTTP implementation distinguishes malformed requests,
denied access, missing repositories and failures after streaming. Narrowing that
classification changes the behavior established by `1818c028` and the current tests.

**Contract.** Malformed client input, absence, authorization failure and backend failure must remain distinct.
An unexpected internal failure must not expose incidental exception text as a client error. Setting status 200
alone does not commit a response; writing or flushing may. After commitment there can be no replacement HTTP
status. Existing 403 behavior for missing or invalid Bearer credentials is separately covered and need not
change with this repair.

**Minimal repair.** Translate expected validation and typed provider failures at their owning boundary.
Preserve absence information through the Git service boundary, remove message matching, and let unexpected
exceptions produce a sanitized server error. Before commitment translate failures normally; afterward
terminate the stream through its existing mechanism.

**Alternatives and consequences.** A universal HTTP error framework is unnecessary; local typed outcomes and
existing result codes can express these distinctions. Keeping the generic 400 catch misclassifies backend
faults. Changing 400 to 500 globally without identifying current validation throws would misclassify legitimate
client errors. New tests must exercise missing repositories, corrupt/backend state and failures before and
after actual response commitment.

**Confidence.** High on the inspected servlet and pack mappings. Git parser/storage internals were
excluded from this audit; no current claim is made about their persisted-metadata failure paths. The exact
typed replacement must follow the owning service boundaries.

**Priority signals.** Importance: high, because internal argument-contract failures can be exposed as client
errors with incidental messages, and streaming failure behavior is ambiguous after commitment. Repair ease:
low, because absence and typed failures cross Git service, storage and HTTP boundaries and need pre- and
post-commit verification.

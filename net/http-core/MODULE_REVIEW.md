# Module Review: net/http-core

## 4. Repository spelling can select one storage identity under another ACL identity

**Problem.** Published-pack requests authorize a normalized repository name but pass the original spelling to
storage. Smart HTTP also removes a .git suffix in bootstrap and removes another in its access hook. An admin
can create storage name team/project.git from team/project.git.git; a request to
/r/team/project.git.git/info/refs then uses storage name team/project.git and ACL name team/project.
These are distinct storage identities. Frontend creation introduces another normalization pass.

**Sources.** [Admin normalization](src/main/java/pro/deta/orion/transport/http/OrionAdminCreateRepositoryRoute.java#L58),
[pack lookup](src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java#L107),
[pack ACL normalization](src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java#L117),
[Git bootstrap normalization](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireBootstrap.java#L257),
[Git ACL normalization](../git-transport/src/main/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHook.java#L145),
[storage identity](../../git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/FileNativeGitRepositoryProvider.java#L148),
[frontend creation](../frontend/ui/src/lib/orion-api.js#L71),
[pack test with different storage and ACL names](src/test/java/pro/deta/orion/transport/http/OrionGitPackfileRouteTest.java#L50), and
[canonical advertised pack URI](src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java#L93).

**Documented behavior.** The
[Smart HTTP plan](../../docs/plans/2026-05-15-git-smart-http-transport-adapters.md#L141)
requires repository decoding and normalization once, before authorization.

**Contract.** Repository and branch authorization must refer to exactly the repository opened or modified.
Preserve ordinary nested names and established single-suffix aliases without silently merging distinct
persisted storage identities. No deployed inventory of suffix-bearing or whitespace-bearing names was
available.

**Minimal repair.** Establish one external-spelling conversion and pass its result unchanged to storage and
ACL construction. Remove additional suffix stripping from canonical-name consumers and frontend submission.
Update HTTP creation, Smart HTTP, pack downloads and affected SSH access-hook consumers together. Reuse or
relocate the existing normalization logic before adding a new public value type.

**Alternatives and consequences.** Fixing only pack lookup leaves the Smart HTTP double-normalization path.
Stripping every trailing .git would collapse existing distinct names and is not a safe repair. A new neutral
RepositoryName type is justified only if the inspected consumers cannot maintain the canonical boundary with
the existing representation. Rejecting or migrating existing ambiguous names changes persisted compatibility
and requires an explicit inventory and decision. Tests must cover distinct name and name.git repositories
with different grants.

**Confidence.** High on current identity divergence and its authorization consequence; deployment migration
requirements remain unknown.

**Priority signals.** Importance: high, because a concrete spelling can authorize one repository identity
while opening another. Repair ease: low, because all HTTP and SSH consumers must change atomically and existing
persisted suffix-bearing names require an inventory before canonicalization.

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
[typed-matching task](../../docs/plans/upcoming-work/11_http-core-hardening/02_typed-route-matching.md)
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
/session-hostile case still ends in 404. Repair ease: low, because the safe replacement depends on unified
invocation and must preserve overlapping Git, fallback and authorization behavior across several handlers.

## 5. HTTP failure classification loses domain meaning and response commitment

**Problem.** The servlet converts every IllegalStateException into HTTP 400 with its message. Missing legacy
Git discovery produces that exception directly, bypassing the Git route's IOException-only missing-repository
scan. Invalid persisted repository metadata also reaches this client-error mapping. The pack route discards
all provider failure codes, and the gzip wrapper labels every underlying I/O failure as invalid gzip.
Streaming handlers attempt HTTP error translation without checking whether protocol output is already
committed.

**Sources.** [Servlet exception mapping](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteServlet.java#L28),
[repository-service failure conversion](../git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java#L94),
[persisted metadata failure](../../git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/FileNativeGitRepositoryProvider.java#L123),
[Git catches](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L109),
[message scan](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L334),
[pack result conversion](src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java#L107),
[gzip conversion](src/main/java/pro/deta/orion/transport/http/GitHttpRequestBody.java#L24), and
[current gzip tests](src/test/java/pro/deta/orion/transport/http/GitHttpRequestBodyTest.java#L49).

**Documented behavior.** The
[Smart HTTP failure contract](../../docs/plans/2026-05-15-git-smart-http-transport-adapters.md#L214)
distinguishes malformed requests, denied access, missing repositories and failures after streaming.
The [gzip plan](../../docs/plans/2026-09-02-smart-http-compressed-request-bodies.md#L64) explicitly required the
current broad I/O wrapping; narrowing that classification changes that documented implementation decision.

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

**Confidence.** High on current misclassification; the exact typed replacement should follow existing service
boundaries.

**Priority signals.** Importance: high, because backend and persisted-state failures can be exposed as client
errors with incidental messages, and streaming failure behavior is ambiguous after commitment. Repair ease:
low, because absence and typed failures cross Git service, storage and HTTP boundaries and need pre- and
post-commit verification.

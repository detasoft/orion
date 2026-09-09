# Module Review: net/http-core

## 2. Route invocation and policy have parallel production paths

**Problem.** OrionHttpRoute publicly exposes both a returned-response operation and a direct servlet operation.
The returned-response default throws for direct handlers. Buffered, streaming, shutdown and asynchronous Agent
routes independently own method dispatch, policy checks and response lifetime. Adding a route requires knowing
which invocation protocol applies, while the route table copies separate descriptive values.

**Sources.** [Invocation defaults](src/main/java/pro/deta/orion/transport/http/OrionHttpRoute.java#L17),
[buffered dispatch](src/main/java/pro/deta/orion/transport/http/AbstractOrionHttpRoute.java#L44),
[metadata construction](src/main/java/pro/deta/orion/transport/http/OrionHttpRouteRegistry.java#L56),
[Git method dispatch](src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L83),
[shutdown ordering](src/main/java/pro/deta/orion/transport/http/OrionAdminShutdownRoute.java#L24),
[asynchronous Agent route](src/main/java/pro/deta/orion/transport/http/AgentControlRoute.java#L91),
[route-table consumer](../frontend/ui/src/App.vue#L428), and
[production route-contract coverage](../../tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpAdminApiIT.java#L26).

**Documented behavior.** The
[unified-invocation task](../../docs/plans/upcoming-work/11_http-core-hardening/01_unified-route-invocation.md)
requires one invocation and policy boundary. The
[Agent transport plan](../../docs/plans/2026-09-08-agent-server-http2-control-transport.md#L68)
requires asynchronous full-duplex operation and protocol-owned authentication.

**Contract.** Preserve method-specific Allow, fail-closed admin and repository checks, streaming and
backpressure, Agent handshake ownership, and shutdown's flush-before-publish ordering. Route metadata is
currently consumed as descriptive UI data; no current admin-policy bypass was demonstrated. Different route
method sets are not themselves a defect.

**Minimal repair.** Make handle the sole public route invocation, keeping buffered response construction an
implementation detail. Consolidate method and executable coarse-policy dispatch at the existing servlet
boundary, deriving descriptive metadata from it. Add only the exchange capabilities required by current
buffered, streaming and asynchronous callers; preserve feature-owned repository and Agent checks.

**Alternatives and consequences.** Removing only the public throwing service default is a smaller first
slice, but leaves policy duplication and does not complete the existing task. A generalized web framework or
new operation lifecycle is unnecessary. An exchange that assumes synchronous completion would break Agent
control; forced buffering would break Git and downloads. Preserve the existing route-table JSON fields used by
the UI.

**Confidence.** High on duplicated invocation and policy ownership; route-specific behavior must be
characterized before consolidation.

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

## 6. The public configuration schema rejects a supported object collection

**Problem.** Schema generation drops generic field types and describes every collection as strings.
bootstrap.keyMaterial.serverSigning.verification accepts objects with alias and version, but its public
schema advertises string items. The endpoint therefore publishes a second configuration language that rejects
valid input.

**Sources.** [Published schema declaration](src/main/java/pro/deta/orion/transport/http/OrionConfigurationJsonSchema.java#L21),
[collection inference](src/main/java/pro/deta/orion/transport/http/OrionConfigurationJsonSchema.java#L59),
[configuration type](../../core/schema/src/main/java/pro/deta/orion/schema/config/ServerSigningConfig.java#L12),
[actual YAML/TOML loaders](../../connectors/configuration-location/src/main/java/pro/deta/orion/config/LocationConfigurationProvider.java#L129),
[accepted object-list fixture](../../connectors/configuration-location/src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java#L69), and
[limited schema test](src/test/java/pro/deta/orion/transport/http/OrionConfigurationJsonSchemaTest.java#L12).

**Documented behavior.** The [README](../../README.md#L315) publishes this endpoint as a configuration JSON schema.
The generated document declares Draft 2020-12 and YAML/TOML configuration coverage.

**Contract.** Valid supported configuration objects must conform to the advertised structural schema.
Current maps inspected here contain strings; their generic handling is not a separate demonstrated mismatch.
No in-repository validation consumer was found, but that does not remove the publicly documented schema
contract.

**Minimal repair.** Preserve generic element types in the existing generator and add conformance coverage using
representative input accepted by the real configuration loader. Keep the serving route thin. Place shared
schema logic with configuration ownership only where that eliminates duplicate property interpretation;
moving the same inference unchanged does not solve the defect.

**Alternatives and consequences.** An explicit schema for the current configuration graph avoids reflection
but must have owner-level conformance checks. Weakening the endpoint to UI hints or removing restrictive schema
keywords changes the published validation guarantee and is not justified by the absence of a local consumer.
Do not add a general schema framework solely for this object list.

**Confidence.** High on the current collection mismatch; broader serialization-property conformance needs
focused verification.

## 7. Concurrent ACME issuance can exhaust workers needed for HTTP-01 callbacks

**Problem.** Each admin issuance request synchronously waits for CA authorization and order completion on a
Jetty worker. HTTP-01 callbacks use the same pool, whose maximum size is ten including infrastructure work.
Enough concurrent issuance requests can exhaust available request workers while all await callbacks. The
singleton service admits competing issuance operations without a non-waiting bound.

**Sources.** [Admin POST](src/main/java/pro/deta/orion/transport/http/OrionAdminAcmeCertificateRoute.java#L39),
[issuance operation](src/main/java/pro/deta/orion/transport/http/AcmeCertificateService.java#L53),
[challenge wait and cleanup](src/main/java/pro/deta/orion/transport/http/AcmeCertificateIssuer.java#L62),
[order waits](src/main/java/pro/deta/orion/transport/http/AcmeCertificateIssuer.java#L80),
[shared Jetty pool](src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java#L114), and
[issuer behavior tests](src/test/java/pro/deta/orion/transport/http/AcmeCertificateIssuerTest.java#L19).

**Documented behavior.** The
[ACME migration plan](../../docs/plans/2026-09-03-acme-key-material-migration.md#L15)
requires material-owned keys and durable installation before successful public-chain output. It does not
require a new asynchronous operation/status API.

**Contract.** Preserve synchronous success with public certificate-chain PEM, durable installation before
success, material ownership, admin authorization and challenge removal after failure. Preserve callback
capacity during admitted issuance. There is no verified requirement to accept concurrent issuance against the
single configured certificate identity.

**Minimal repair.** Add non-waiting single-flight admission to the existing certificate service and release it
in finally. Reject concurrent issuance promptly with an explicit busy result at the HTTP boundary. Preserve
the successful request contract and prove that HTTP-01 remains serviceable while issuance waits.

**Alternatives and consequences.** Waiting on a semaphore inside each request still consumes workers and does
not solve starvation. A 202/status API adds operation state and changes clients; use it only if detached
issuance is required. Admission control introduces a retryable busy outcome and limits issuance concurrency,
but does not need a new service or persistence model. Increasing the thread pool alone cannot bound issuance.

**Confidence.** High on shared workers and missing admission control; expected production concurrency was not
measured.

## 10. Agent stream shutdown has an unresolved cleanup verification gap

**Problem.** Jetty stop and per-stream application cleanup have no explicit shared completion boundary.
The application loop waits on its inbound queue and invokes onClosed only when it exits; stream finish is
driven by asynchronous events or the handshake deadline. The shutdown test counts unlabelled callbacks without
first establishing that every application handler opened, so a missing callback cannot distinguish startup
ordering, delayed cleanup or a stream that never finishes.

**Sources.** [Server stop](src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java#L227),
[application startup and cleanup](src/main/java/pro/deta/orion/transport/http/AgentControlRoute.java#L153),
[stream finish](src/main/java/pro/deta/orion/transport/http/AgentControlRoute.java#L358),
[handler boundary](src/main/java/pro/deta/orion/transport/http/AgentControlHandler.java#L7), and
[shutdown test](src/test/java/pro/deta/orion/transport/http/JettyHTTPServerTest.java#L464).

**Documented behavior.** The
[HTTP/2 transport plan](../../docs/plans/2026-09-08-agent-server-http2-control-transport.md#L95)
requires EOF, reset, disconnect, failed writes, deadline and server stop to converge on idempotent cleanup of
pending sends and timers.

**Contract.** Every admitted stream must release its transport-owned work on shutdown and settle pending sends.
The plan does not establish that arbitrary application callbacks must finish synchronously before onStop
returns. A permanent leak has not been proved by static inspection.

**Minimal repair.** Make the lifecycle test identify every opened stream, await its admission and observe its
individual completion and pending-send settlement across shutdown. Use that evidence to connect any missing
stop notification to the existing idempotent finish path; do not create a second stream registry before its
necessity is established.

**Alternatives and consequences.** If existing container callbacks satisfy the contract once admission is
synchronized, only test correction is needed. If they do not, add the narrow lifecycle link demonstrated by
the failure. Extending polling time or removing assertions would not establish cleanup. Requiring synchronous
completion of arbitrary application work could make shutdown unbounded and is a separate contract change.

**Confidence.** Medium: the current ownership and test ambiguity are verified; the runtime cause of the
recorded missing callback remains unresolved.

## 9. HTTPS tests release their port before Jetty binds it

**Problem.** The HTTPS fixture selects an ephemeral port with a temporary ServerSocket, closes it, and later
starts Jetty on that number. Another process or concurrent test can acquire the port in between, making an
unrelated behavior test fail at server startup.

**Sources.** [Fixture port selection](src/test/java/pro/deta/orion/transport/http/JettyHTTPServerTest.java#L663),
[temporary reservation](../../core/common/src/main/java/pro/deta/orion/util/NetworkUtils.java#L25),
[affected startup](src/test/java/pro/deta/orion/transport/http/JettyHTTPServerTest.java#L390),
[HTTPS port validation](../../core/schema/src/main/java/pro/deta/orion/schema/orion/OrionHttpsConfiguration.java#L20), and
[failed-start cleanup](src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java#L210).

**Documented behavior.** The
[HTTP/2 validation plan](../../docs/plans/2026-09-08-agent-server-http2-control-transport.md#L101)
requires live endpoint verification. The HTTPS domain model currently permits ports 1 through 65535.

**Contract.** Behavioral tests must not depend on an unreserved port remaining free. Existing HTTPS desired
state rejects port 0; replacing the fixture value with 0 alone violates that contract.

**Minimal repair.** Preserve production port validation and make fixture startup retry a fresh selection only
for a confirmed bind collision, with a small explicit bound and complete failed-server cleanup. Keep the test
that intentionally occupies a configured port outside that helper.

**Alternatives and consequences.** Allowing port 0 and using boundHttpsPort removes the reservation gap
completely, but changes HTTPS domain/schema validation and needs coordinated tests and operator semantics.
Unbounded retries can mask real startup faults; retrying only known fixture bind collisions contains that
risk. A retry limit must still report the final failure.

**Confidence.** High on the race and port-0 restriction; collision frequency is environment-dependent.

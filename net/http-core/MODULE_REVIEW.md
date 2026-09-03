# Module Review: `net/http-core`

Date: 2026-09-03
Status: reviewed in isolation

## Scope and coverage

This review covers the module POM, all 39 production classes, all 17 test classes and helpers, test resources,
the three files at the module root, and module-specific history. The production surface includes Jetty
lifecycle and connector setup, authentication filtering, route registration and dispatch, response writing,
administrative APIs, Smart HTTP Git, published pack downloads, frontend resources, session-host downloads,
ACME issuance and challenges, and configuration-schema publication.

The review treats imported types as contracts visible through their use in this module. Only two declarations
outside the module were inspected to validate assumptions made here: the configured collection element type and
the currently imported Git path normalizer. `net-core`, `transport`, and their consumers were not reviewed.

This was a static architecture review. Maven verification was not run because repository review rules assign
verification to implementation work. The current suite contains 85 `@Test` methods; one manual ACME test is
disabled.

## Current conceptual model

`http-core` is the concrete application HTTP adapter, despite its `core` name. It currently owns this request
path:

1. `JettyHTTPServer` creates HTTP and HTTPS connectors and mounts one servlet plus one authentication filter.
2. `OrionAuthorizationFilter` turns an optional Bearer token into a request `SecurityContext`.
3. `OrionHttpRouteServlet` selects one route by exact string or wildcard pattern and performs broad exception
   translation.
4. A route either returns an `OrionHttpResponse` for `OrionHttpResponseWriter`, or bypasses that model and writes
   directly to the servlet response.

The returned-response path is used by the admin, schema, challenge, and frontend routes. Git, packfile, and
session-host routes use direct response writing because they stream data or perform their own content
negotiation. The shutdown route is a third hybrid: it obtains a returned response, writes and flushes it, then
publishes a shutdown event.

The route registry also exposes a descriptive route table. It copies URL pattern, allowed methods, and an
authorization label from each handler, but does not enforce the latter two values. Enforcement remains inside
the selected route.

The same module additionally owns two workflows behind routes:

- Smart HTTP adapts servlet streams and identity to the Git wire service and native pack storage.
- ACME synchronously creates or reads private keys, answers HTTP-01 challenges through an in-memory map, waits
  for the CA, builds a certificate-plus-private-key PEM, and optionally persists or downloads it.

## Highest-value findings

### 1. ACME private-key lifecycle crosses source control, filesystem persistence, and HTTP responses

**Finding.** Private key material has no single protected owner. A disabled manual test writes keys into the
module working directory, two resulting private keys are tracked in Git with ordinary file mode, production
code writes new private keys without requesting owner-only permissions, and the admin API returns a combined
certificate and private key.

This is a blocking security finding under the repository review rules. The checked-in certificate is an expired
Let's Encrypt staging certificate, but that does not make the corresponding private keys safe to publish. The
tracked keys must be treated as disclosed.

**Evidence.** Git tracks `account.keypair` and `domain.keypair` as mode `100644`; both are PEM EC private keys and
are also locally readable as `0644`. `domain.crt` is tracked beside them. The disabled
`createAcmeAccountManually` test selects the relative names at
`ACMECertificateChallengeTest.java:32-33`, reads or creates them at lines 106-117, and writes `domain.crt` at
lines 68-87. The three files have been present since the initial module history.

Production `AcmeCertificateService.readOrCreateKeyPair` uses an ordinary `Files.newBufferedWriter` at lines
135-149, and `writeNginxCertificate` uses `Files.writeString` at lines 152-157. Neither establishes restrictive
permissions or atomic publication. `IssuedAcmeCertificate.nginxPem` deliberately joins the private key to the
certificate at lines 13-17, and `OrionAdminAcmeCertificateRoute` makes that combined value downloadable at
lines 23-48.

**Why it likely exists.** An early manual ACME experiment persisted convenient working-directory files. The
same raw-file model then became the production certificate service, while the HTTP result model retained the
private key so an operator could install one nginx-compatible file.

**Simpler model.** Give key generation, loading, rotation, permissions, and atomic persistence to one key-material
component. The HTTP route should request certificate issuance using opaque key references and should not decide
how private keys are stored. If exporting a combined nginx PEM is a required administrative operation, model it
as an explicit privileged export rather than the default certificate value.

Delete the checked-in files, prevent manual tests from writing below the source tree, and rotate both keys.
Assess whether repository history must be purged according to the repository's distribution and secret-response
policy; deleting only the current files does not retract their history.

**Contract change.** Issuance would return certificate data plus key references by default. A private-key export,
if retained, becomes an explicit capability. Configured file storage must guarantee owner-only permissions and
atomic replacement rather than inheriting process defaults.

**Consequences.** Source checkout and test execution can no longer create publishable secrets accidentally.
Concurrent issuance cannot race raw key-file creation, and key storage can later move to a keystore without
changing the HTTP contract.

**Confidence.** High on the exposure and unsafe ownership; medium on removing private-key export because nginx
deployment requirements were not reviewed.

### 2. `OrionHttpRoute` is two execution protocols behind one interface

**Finding.** A route may be a function returning a buffered value or an imperative servlet handler, and callers
cannot tell which contract applies. Method handling, authorization, HEAD behavior, response serialization, and
error translation consequently vary by route even though the interface advertises one uniform route table.

**Evidence.** `OrionHttpRoute.handle` calls `service` by default at `OrionHttpRoute.java:17-22`, while its default
`service` throws `UnsupportedOperationException` at lines 24-25. `AbstractOrionHttpRoute.service` owns method
dispatch and authorization at lines 44-62. `OrionGitRoute`, `OrionGitPackfileRoute`, and
`SessionHostDownloadRoute` override `handle`, manually repeat method checks, and write directly. The shutdown
route overrides `handle` for post-write side effects at `OrionAdminShutdownRoute.java:24-34`.

`authorization()` is only a string label. `OrionHttpRouteRegistry` copies it and `allowedMethods()` into the
introspection table at lines 56-61 but does not enforce either value. For example, Git advertises one global
method list and separately computes endpoint-specific `Allow` values in `OrionGitRoute.java:83-99` and
294-310. A handler can therefore advertise `application-admin` without performing that check, or enforce a
method set different from the table, without the registry noticing.

History supports the structural cause: the returned-response route API was introduced first; direct writing was
then added under a default method, and HEAD suppression was added later without removing the older protocol.

**Why it likely exists.** Returning a small JSON response is convenient, while Git and binary downloads need
streaming. The second need was added as an escape hatch rather than making streaming and buffered bodies two
explicit response choices under one invocation contract.

**Simpler model.** Give every route one public invocation method. A canonical immutable route definition should
contain a path matcher, typed HTTP methods, an executable coarse authorization policy, and one handler. The
servlet should apply matching, method checks, and the declared policy once. The handler should receive a small
HTTP exchange that can either send a typed buffered body or open a streaming body. A base class may remain as a
convenience for small JSON routes, but it should not create a second public protocol.

Resource-specific authorization, such as repository grants, can remain in the feature handler after a typed
path match. Its descriptive route-table value should be derived from the executable policy rather than supplied
as an unrelated string.

**Contract change.** Remove `service()` and the default throwing state from `OrionHttpRoute`. Replace raw method
and authorization strings with typed, enforced route-definition values. Direct handler invocation in tests
would go through the same preflight boundary as production.

**Consequences.** HEAD, `Allow`, authentication failure, and response commitment become consistent without
buffering Git or binary content. The shutdown route still needs an explicit after-flush action, but no longer
needs to override an otherwise unrelated dispatch protocol.

**Confidence.** High.

### 3. Matching and path interpretation are separate, inconsistent operations

**Finding.** The registry uses a general character wildcard only to choose a handler, discards all matched
structure, and makes the selected handler parse the path again. The wildcard language is stronger than the
actual routing needs and allows a route to capture paths it then rejects.

**Evidence.** `WildcardMatcher` lets `*` match any number of arbitrary characters, including `/`, using a
two-dimensional table at lines 16-45. `OrionHttpRouteRegistry` orders overlapping patterns by the count of
non-asterisk characters at lines 63-66; it validates duplicate pattern strings but not ambiguous matches.

The production patterns include `/*`, `/session-host*`, `/r/*`, and `/r/*/objects/pack/*.pack`. Therefore
`/session-hostile` selects `SessionHostDownloadRoute` instead of the frontend catch-all. That route recognizes
that the path is outside `/session-host/` only after selection and returns a synthetic empty target at
`SessionHostDownloadRoute.java:96-103`, which becomes 404. The two Git patterns intentionally overlap and rely
on the registry's string-length heuristic to select the pack handler.

The servlet, Git handler, pack handler, session-host handler, frontend handler, and ACME challenge handler each
recover paths independently. The fallback implementations are not identical: the Git and pack handlers strip a
context path from `requestURI`, while the servlet and frontend handler do not. `routeFor` returns only a handler,
so none of these parsers can reuse captures or a normalized remainder from routing.

**Why it likely exists.** Wildcard matching provided a small replacement for a servlet mapping table. As
feature routes needed parameters, extraction stayed local, and specificity sorting was added to preserve a
more-specialized Git pack route above `/r/*`.

**Simpler model.** Support only exact and segment-aware prefix/template routes, and have matching return a typed
result containing path parameters or the unmatched remainder. Reject ambiguous registrations at startup. Give
the `/r/` namespace one registered owner that delegates internally between Smart HTTP and published-pack
handling, so repository parsing and authorization cannot diverge across overlapping registry entries.

Use exact `/session-host` plus a segment template for its target rather than `/session-host*`. Once all current
patterns are represented, delete the general wildcard matcher and every handler-local `routePath` copy.

**Contract change.** Paths that matched only by character prefix, such as `/session-hostile`, will fall through
to the intended catch-all or 404. Routes receive decoded path structure instead of the raw servlet request as
their only source of identity. Context-path handling becomes one adapter decision.

**Consequences.** Routing no longer depends on registration pattern spelling, overlap becomes explicit, and
handler tests can exercise the same parsed values production supplies. The replacement should remain small;
introducing a second full web framework is not justified.

**Confidence.** High.

### 4. Repository identity has three local meanings

**Finding.** Administrative creation, Smart HTTP, and published-pack download do not share one repository-name
boundary. Storage lookup and ACL lookup can even use different names within one pack request.

**Evidence.** `OrionAdminCreateRepositoryRoute.normalizeRepositoryName` strips leading slashes and one `.git`
suffix, then rejects NUL, backslash, and any `..` substring at lines 58-70. `OrionGitRoute` passes its extracted
path to the imported Git bootstrap, which performs a separate normalization. `OrionGitPackfileRoute` parses the
raw substring at lines 154-176 and passes it unchanged to `repositoryProvider.find` at lines 107-114, but strips
leading slashes and `.git` only for the authorization resource at lines 117-141.

As a result, `/r/team/project.git/objects/pack/...` authorizes `team/project` while looking up
`team/project.git`. The pack route test constructs exactly that differently named storage entry, whereas the
admin route would create `team/project` from the same external spelling. Validation rules also differ between
the three entry points.

**Why it likely exists.** Each transport initially received protocol-shaped repository strings. Normalization
was added near the first consumer that needed it, leaving `.git` as a presentation suffix in some flows and a
storage-key character sequence in another.

**Simpler model.** Introduce one small canonical `RepositoryName` parser in a neutral lower-level contract that
does not depend on `git-parser`. HTTP path extraction, SSH commands, administrative JSON, ACL resource creation,
and storage lookup should all convert external spelling to that value once. Transport-specific syntax such as a
leading slash or `.git` suffix belongs in adapters before construction.

**Contract change.** Aliases resolve to one storage and ACL identity, and inputs accepted by only one current
path become either consistently valid or consistently rejected. Existing stored names containing presentation
syntax may require migration.

**Consequences.** A repository cannot be authorized under one name and loaded under another. Validation and
future path-hardening work have one owner without coupling every caller to the Git wire parser.

**Confidence.** High on the current divergence; medium on migration needs because stored repository names were
not inventoried.

### 5. HTTP status and error semantics are inferred from incidental exception shapes

**Finding.** There is no module-wide error vocabulary. Equivalent failures are represented by response values,
`sendError`, checked exceptions, unchecked exceptions, result codes, cause scans, or message text. Some internal
failures are reported as client errors, and some streaming errors are translated after the success response may
already be committed.

**Evidence.** `OrionHttpRouteServlet` maps every `IllegalArgumentException` and `IllegalStateException` to 400
and returns the exception message at lines 28-40. A non-`FILE_ALREADY_EXISTS` repository creation failure calls
`Result.valueOrFailure` at `OrionAdminCreateRepositoryRoute.java:46-50`; that helper throws
`IllegalStateException`, so a storage/backend failure becomes 400. Conversely, `OrionGitPackfileRoute` maps
every provider `Result.Failure` to an absent repository and then 404 at lines 86-95 and 107-114.

`OrionGitRoute` finds a missing repository by searching nested exception messages for
`Native repository does not exist` at lines 334-343. `GitHttpRequestBody` wraps every `IOException` from read,
skip, available, and close as invalid gzip at lines 24-83, so an underlying transport or close failure can be
reported as malformed client content. Buffered routes return empty or JSON response values, while direct routes
call `sendError`, producing a different error representation.

Git discovery and POST set status 200 and content type before the wire session completes at
`OrionGitRoute.java:134-178`. The surrounding catch then attempts to change the status for later access, input,
missing-repository, or backend errors. Once any protocol bytes are flushed, that translation cannot reliably
change the HTTP response.

**Why it likely exists.** The servlet offered a convenient global catch for validation errors. Direct streaming
routes later needed finer Git mappings, but the imported services did not expose a transport-neutral failure
type, so the adapter inferred intent from generic exceptions and messages.

**Simpler model.** Define a small HTTP-boundary failure vocabulary: malformed request, unauthenticated,
forbidden, not found, conflict, unavailable, and unexpected internal failure. Domain adapters should translate
typed service results into that vocabulary before committing headers. Unexpected exceptions should be logged
and become a message-free 500. After a streaming response is committed, failures should terminate/log the
stream rather than claim that a new status can be sent.

Gzip syntax errors should be classified at the decompressor boundary without reclassifying unrelated source
and close failures. Git service absence and access denial need typed outcomes instead of message inspection.

**Contract change.** Several current status codes change: backend failures no longer appear as 400 or 404,
authentication can distinguish 401 from authorization's 403, and error bodies become consistent. A stream that
fails after commitment has no second HTTP status.

**Consequences.** Retry, observability, and client behavior can depend on stable semantics. Tests must cover the
real servlet pipeline because direct route mocks do not model response commitment.

**Confidence.** High.

### 6. The published configuration schema is a lossy second model of configuration

**Finding.** `OrionConfigurationJsonSchema` claims to publish a Draft 2020-12 schema for accepted YAML and TOML,
but reconstructs that contract from raw Java fields with rules that are already false for current configuration
types. Because every object also declares `additionalProperties: false`, the generated document is stricter
than its incomplete understanding warrants.

**Evidence.** `fieldSchema` declares every `Collection` to contain strings and every `Map` to have string values
at `OrionConfigurationJsonSchema.java:44-69`; it does not inspect generic element types or serialization
metadata. Current configuration includes `List<SigningKeyReferenceConfig> verification`, which is consequently
advertised as an array of strings rather than objects with alias and version. The generator reads private fields
with `setAccessible`, silently substitutes an empty object model when construction fails, and derives names
from fields rather than the actual configuration binding at lines 95-133.

No module test mentions `OrionConfigurationJsonSchema` or asserts the generated schema against valid and invalid
configuration examples. History shows that the generator has not changed since introduction except for a
package rename, while the configuration model has continued evolving.

**Why it likely exists.** Reflection avoided manually maintaining a large schema and was adequate while the
configuration graph contained only primitive fields and string collections. The HTTP endpoint then became the
owner of a contract that actually belongs to configuration serialization.

**Simpler model.** Make the configuration/schema module own one generated or explicit schema from the same
property model used to deserialize configuration. Preserve generic element types, names, defaults, and
constraints there. `http-core` should only serve a schema document supplied by that owner; it should not infer
configuration semantics.

If the endpoint is only UI metadata rather than validation, name and document that weaker contract instead of
publishing it as a restrictive JSON Schema.

**Contract change.** The schema for object collections changes incompatibly but becomes consistent with inputs
the server already accepts. Future configuration fields must update or validate the schema at their owning
boundary.

**Consequences.** Configuration evolution no longer silently creates a second incompatible language. Schema
tests can round-trip representative default, nested-object, list-of-object, and map configurations independent
of Jetty.

**Confidence.** High.

### 7. ACME issuance blocks the same bounded server needed to answer its callback

**Finding.** Certificate issuance is a long-running, stateful operation executed synchronously on a Jetty
request worker, while the CA's HTTP-01 callbacks must be served by that same ten-thread pool. There is no
single-flight or bounded issuance owner, and concurrent requests also share key paths and challenge state.

**Evidence.** `OrionAdminAcmeCertificateRoute.doPost` calls `certificateService.issue` synchronously at lines
30-34. `AcmeCertificateIssuer` registers each challenge, triggers it, and waits for authorization completion at
lines 47-81, then waits for order readiness and completion at lines 84-97. The configured timeouts may apply
multiple times and once per domain. `JettyHTTPServer` constructs one `QueuedThreadPool(10, 2, 120)` for all admin
requests and challenge GETs at lines 93-112.

Ten concurrent issuance requests can occupy every worker while all require inbound challenge requests to make
progress. Even below exhaustion, an HTTP connection and worker remain held for an externally timed workflow.
Concurrent calls can enter `readOrCreateKeyPair` for the same files without synchronization or atomic create.

**Why it likely exists.** The original manual issuance flow returned a certificate directly. Exposing that
method as a POST preserved simple call/return behavior but implicitly made Jetty own operation lifetime and
concurrency.

**Simpler model.** Own ACME issuance as one bounded, lifecycle-managed operation service, with admission control
for each configured certificate/key identity. POST should normally start or join an operation and return 202
plus an operation/status location; the challenge route remains a fast independent read from registered state.
Do not create one extra thread per request merely to retain the synchronous contract.

If a synchronous API is mandatory, enforce a small issuance concurrency bound that always reserves HTTP worker
capacity for challenges and serialize access to each key identity. That is a weaker operational model but still
makes resource ownership explicit.

**Contract change.** The preferred HTTP API becomes asynchronous and idempotent for an issuance identity.
Timeouts and cancellation belong to the operation, not the client connection.

**Consequences.** Challenge handling cannot deadlock behind issuance requests, disconnects do not ambiguously
own certificate issuance, and duplicate admin requests cannot race key creation or orders.

**Confidence.** High on shared bounded-worker usage and absence of admission control; medium on expected request
concurrency.

## Smaller inconsistencies and coverage gaps

- The artifact behaves as a top-level application HTTP adapter, not a reusable `core`: its POM directly depends
  on frontend, authorization, schema, native Git storage, Git parsing, Git transport, Jetty, Jackson, and ACME.
  Splitting one module per endpoint would add boundaries without evidence. A later rename to `http-server` or
  `http-transport` and narrower visibility would state the current role more honestly.
- `JettyHTTPServer.onStart` logs both HTTP and HTTPS as listening even when one connector is disabled, and logs
  configured rather than bound ports at lines 59-72. `relativiseHttp` and `relativiseHttps` also use configured
  ports at lines 234-239, so port `0` produces unusable URLs despite bound-port accessors already existing.
- HTTP and HTTPS connector setup ignores the configured `backlog` value. The hard-coded Jetty thread pool also
  has no visible relationship to configuration, so exposed capacity settings and actual HTTP capacity can
  drift.
- `OrionGitRoute` rejects a null `GitTransportConfig` in its constructor but still checks it for null at line
  207. The branch is unreachable and obscures which configuration states are supported.
- Failed or malformed Bearer credentials are silently converted to an anonymous context by
  `OrionAuthorizationFilter`. Protected routes then usually answer 403, whereas the Basic token endpoint answers
  401 with a challenge. Authentication failure and absence therefore have inconsistent HTTP semantics.
- Most feature tests call handlers directly with servlet proxies. The only registry/servlet tests use synthetic
  routes, so the production route set is not tested for overlap, advertised-policy drift, filter integration,
  context paths, committed-response failures, or catch-all behavior.
- There are no module-local route tests for raw ACL update, user update, token issuance, route introspection,
  lifecycle status, shutdown ordering, or configuration schema. External integration coverage was outside this
  review.
- The package-private `OrionAdminTransportsRoute(OrionConfiguration)` constructor exists to inject null providers
  in tests but is not marked with the repository's `@TestOnly` annotation.

## Things to try deleting

- Tracked `account.keypair`, `domain.keypair`, and `domain.crt`, followed by key rotation and an explicit history
  handling decision.
- The disabled manual ACME method's working-directory key generation and certificate output. Keep a separately
  gated integration scenario only if it writes to an explicit temporary or operator-provided directory.
- `OrionHttpRoute.service()` and its throwing default once all routes use one handler contract.
- The raw `authorization()` description if it is not replaced by executable policy metadata.
- `WildcardMatcher`, wildcard specificity sorting, and handler-local copies of `routePath` after segment-aware
  matching returns captures.
- One of the two registered `/r/` route owners; keep internal Git delegates if that helps separate pack serving
  from wire protocol code.
- Exception-message scanning, catch-all `IllegalStateException -> 400`, and `Result.Failure -> Optional.empty`
  translations once typed failures reach the boundary.
- `OrionConfigurationJsonSchema` from this module after configuration owns its schema. Delete the duplicate
  inference, not the HTTP endpoint.
- The unreachable `gitTransportConfig == null` branch and test-only null-provider construction after tests use
  explicit fakes or a marked test-only factory.

## Proposed conceptual model

- `http-core` is treated, and eventually named, as one application HTTP adapter rather than a general core
  library.
- Jetty owns connector lifecycle and one request adapter. The existing raw lifecycle state-machine API remains
  intact.
- One segment-aware route table owns path matching, method checks, coarse executable authorization, and
  descriptive introspection.
- Every route has one invocation contract and chooses a typed buffered body or a streaming body explicitly.
- One HTTP failure vocabulary is mapped before response commitment; post-commit stream failures terminate the
  stream and are observable in logs/metrics.
- One neutral `RepositoryName` value crosses route, ACL, Git service, and storage boundaries.
- Configuration owns its schema; HTTP only publishes it.
- Key material owns private keys; a bounded ACME operation owner coordinates issuance; HTTP only initiates,
  observes, and, if explicitly required, authorizes export.

## Incremental migration path

1. Treat the checked-in keys as disclosed: rotate them, remove generated material from the tree, add ignore and
   test-output safeguards, and decide separately whether history rewriting is required.
2. Add characterization tests through the real registry/filter/servlet pipeline for the production route set:
   exact versus prefix routing, `/session-hostile`, Git pack overlap, methods, 401/403, context paths, backend
   failures, and response commitment.
3. Introduce a segment-aware match result and canonical route definition. Move method and coarse authorization
   enforcement into the servlet adapter, converting buffered routes first and streaming routes second. Preserve
   shutdown's response-flush-before-event ordering.
4. Give `/r/` one route owner and introduce a neutral canonical repository-name parser. Migrate admin creation,
   Smart HTTP, pack lookup, and ACL resource construction together; inventory stored aliases first.
5. Define typed boundary failures and correct status mappings before removing message/cause inference. Move all
   validations and access checks that can fail ahead of streaming response commitment.
6. Move configuration-schema ownership beside configuration binding and add representative schema conformance
   tests. Leave the HTTP schema URL as a thin serving adapter.
7. Move private-key persistence behind a key-material owner, then add bounded/idempotent ACME operation state.
   Change the POST contract to 202/status only after the operation model is covered independently.
8. Correct connector logging, bound-port URL helpers, backlog handling, dead branches, and missing route tests as
   small follow-up changes. Consider an artifact rename only after verifying external Maven consumers.

The security cleanup is urgent. The remaining steps are independently reviewable and should not be combined
into one large routing rewrite.

## Do not change

- Preserve streaming for Git protocol responses, published packs, and session-host binaries. A unified route
  contract must not require buffering large or progressive bodies.
- Preserve exact-match precedence and deterministic route selection while replacing wildcard behavior.
- Preserve the trusted-proxy allowlist and fail-closed forwarded-header parsing in
  `OrionGitPackfileUriBaseResolver`.
- Preserve ACME challenge removal in a `finally` path so failed orders do not leave stale public answers.
- Preserve `Cache-Control: no-store` on certificate/private-key exports for as long as that export exists.
- Preserve the shutdown route's flush-before-publish ordering.
- Preserve `JettyHTTPServerStateMachine`'s raw production state-machine API. Its class-level `@AiRule` explicitly
  defines that contract.
- Preserve fail-closed admin and repository authorization. Centralization must not turn resource-specific ACL
  checks into descriptive metadata only.

## Open questions

- Is downloading the combined certificate and private key an intentional supported API, or was it an
  installation convenience for the current UI?
- Are the tracked key files known disposable staging artifacts, and has the repository ever been distributed
  somewhere that requires history cleanup?
- Is ACME issuance expected to be synchronous for clients, and how many concurrent issuance requests must be
  supported?
- Is the route table consumed as machine-readable security/method metadata or only displayed to administrators?
- Should `/session-host` coexist with frontend paths under the same root, and what should an unknown
  `/session-host...` path return?
- Are `.git`, leading slash, repeated slash, and equivalent path forms contractual repository aliases? Do stored
  repositories currently use more than one form?
- Is the configuration JSON Schema used for actual validation or only editor/UI hints?
- Must Orion run below a non-root servlet context path?
- Is `http-core` consumed outside the Orion runtime build, or can its artifact and public types be renamed and
  narrowed without a compatibility period?

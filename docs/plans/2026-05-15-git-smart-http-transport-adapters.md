# Git Smart HTTP Transport Adapters

## Goal

Add JGit-free smart HTTP and HTTPS transport adapters for Git upload-pack and
receive-pack.

This plan covers two related adapters:

- server-side Orion routes that expose native Git repositories through smart
  HTTP under the existing HTTP/HTTPS transport;
- client-side outbound smart HTTP(S) transport used by native remote fetch,
  native remote push, mirroring, and single-file remote operations.

The shared wire protocol core should continue to own pkt-line, capability,
side-band, protocol v2 command parsing, and report-status helpers. The smart HTTP
adapter owns HTTP routing, service discovery, request/response framing, headers,
authentication, TLS/proxy policy, request limits, and mapping HTTP failures to
typed Git transport failures.

## Current State

`docs/plans/2026-05-14-native-git-wire-protocol-core.md` explicitly does not
implement smart HTTP, SSH, or raw TCP transports beyond stream/session
boundaries. It leaves smart HTTP server-side Git support as a separate adapter
question.

`docs/plans/2026-05-14-native-git-protocol-client-primitives.md` lists smart
HTTP(S) as the first production-grade outbound transport after scripted and
controlled `git://` fixtures. It notes that service discovery, POST framing,
token/basic authentication, TLS validation, redirects, and proxies must be
explicit.

`docs/plans/2026-05-14-native-git-upload-pack-serving.md` and
`docs/plans/2026-05-14-native-git-receive-pack-serving.md` define server-side
Git service behavior, but not HTTP route handling.

`docs/plans/2026-05-14-native-remote-git-single-file-fetch.md`,
`docs/plans/2026-05-14-native-remote-git-single-file-push.md`, and
`docs/plans/2026-05-14-github-gitlab-repository-mirroring.md` need a real
production transport for hosted Git providers. HTTPS is the expected first
choice for token-authenticated public providers.

`docs/plans/2026-05-14-maven-repository-serving.md` states that Orion already
has an HTTP routing layer and Git smart HTTP routing under `/r/*`. This plan
defines the native JGit-free behavior behind those routes.

## Non-Goals

Do not implement Git protocol packet parsing in the HTTP adapter. Use the shared
wire protocol core.

Do not implement repository object selection, pack building, ref updates, or
access policy here. Delegate to native upload-pack, receive-pack, and repository
policy services.

Do not implement SSH transport here.

Do not implement raw `git://` TCP transport here.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code. Tests may use Git CLI or JGit-backed
fixtures for compatibility comparison.

Do not make smart HTTP the only Git transport. Native socket, SSH, scripted, and
future transports should continue to use the same service-level boundaries.

Do not silently follow unsafe redirects, disable TLS validation, or log
credentials.

## Smart HTTP Basics

Git smart HTTP uses two request shapes per service.

Discovery:

```text
GET <repo>/info/refs?service=git-upload-pack
GET <repo>/info/refs?service=git-receive-pack
```

Request body:

```text
POST <repo>/git-upload-pack
POST <repo>/git-receive-pack
```

Relevant content types:

```text
application/x-git-upload-pack-advertisement
application/x-git-receive-pack-advertisement
application/x-git-upload-pack-request
application/x-git-receive-pack-request
application/x-git-upload-pack-result
application/x-git-receive-pack-result
```

A discovery response starts with a service announcement pkt-line, followed by a
flush packet, followed by the service advertisement. The shared protocol core
should encode and decode the Git packet pieces. The HTTP adapter should ensure
the correct route, status code, headers, and byte stream boundaries.

## Adapter Boundaries

Server-side adapter responsibilities:

- parse repository route and service name from HTTP request;
- reject invalid methods, paths, query parameters, and content types;
- authenticate the HTTP request;
- build a Git operation subject from the authenticated principal;
- resolve the repository and service;
- pass request streams to native upload-pack or receive-pack service;
- stream response bytes with correct content type;
- map service failures to HTTP and Git protocol errors;
- apply HTTP request limits and timeout policy;
- emit access logs and operation diagnostics.

Client-side adapter responsibilities:

- build discovery and POST requests for upload-pack and receive-pack;
- send `Git-Protocol: version=2` when requested;
- provide basic, bearer token, or provider-specific auth headers;
- validate TLS and redirect policy;
- apply proxy and timeout configuration;
- expose response body as a packet stream to protocol client primitives;
- map HTTP status codes and response content types to typed failures;
- sanitize diagnostics.

Shared responsibilities:

- content-type constants;
- HTTP header model;
- service path normalization rules;
- request/response size limits;
- typed transport errors.

## Server Route Shape

Define one server-side route family for Git repositories.

Example route:

```text
/r/<repository>/info/refs?service=git-upload-pack
/r/<repository>/info/refs?service=git-receive-pack
/r/<repository>/git-upload-pack
/r/<repository>/git-receive-pack
```

The repository portion must be decoded and normalized once:

- reject path traversal;
- reject empty repository names;
- reject duplicate separators if repository naming policy forbids them;
- preserve case according to repository naming policy;
- support `.git` suffix aliases only if existing compatibility requires them;
- do not expose filesystem paths.

Route matching should not let Maven, admin, static files, ACME challenge, or
other HTTP routes intercept Git service paths.

## Server Discovery Handling

Discovery request validation:

- method must be `GET` or `HEAD` where `HEAD` is supported;
- path must end with `/info/refs`;
- query parameter `service` must be exactly `git-upload-pack` or
  `git-receive-pack`;
- no request body is expected;
- unsupported services return a sanitized error.

Discovery response:

- HTTP status `200` for allowed service advertisement;
- service-specific advertisement content type;
- `Cache-Control: no-cache`;
- packet stream begins with `# service=<service>`;
- flush packet follows service announcement;
- service advertisement follows from upload-pack or receive-pack service.

Authorization:

- upload-pack discovery requires repository read or advertisement permission;
- receive-pack discovery requires repository write or push discovery permission;
- hidden refs must be filtered before advertisement;
- denied discovery should not reveal whether hidden refs exist.

## Server POST Handling

POST request validation:

- method must be `POST`;
- path must end with `/git-upload-pack` or `/git-receive-pack`;
- content type must match the service request type;
- body size and streaming duration must be bounded;
- repository and service must match a valid native service.

POST response:

- HTTP status `200` when the service produces a Git protocol response;
- response content type matches upload-pack or receive-pack result;
- no response body buffering for large packs;
- side-band and pkt-line bytes are streamed exactly as service emits them.

Failure mapping:

- pre-service authentication failure maps to `401` or `403`;
- missing repository maps to `404` or sanitized `403` according to policy;
- malformed HTTP request maps to `400`;
- unsupported service maps to `403` or `404` according to policy;
- service-level Git errors before streaming can be `200` with Git `ERR` packet
  or an HTTP error when no Git session has started;
- after streaming starts, use service-level side-band fatal or clean close rules.

## Protocol Version Selection

For smart HTTP, protocol v2 is requested through the `Git-Protocol` header:

```text
Git-Protocol: version=2
```

Server-side behavior:

- parse the header into transport hints;
- pass requested protocol version to native service selection;
- reject unsupported versions with typed protocol failure;
- do not treat malformed headers as arbitrary capabilities;
- ensure v0/v1 fallback is explicit.

Client-side behavior:

- send `Git-Protocol: version=2` for protocol v2 requests;
- allow configuration to force v0/v1 where needed;
- record negotiated or observed protocol version in diagnostics;
- fail if the remote returns a response shape incompatible with the selected
  protocol and fallback is disabled.

## Authentication And Authorization

Server-side supported auth inputs:

- existing HTTP authenticated session or principal;
- `Authorization: Bearer <token>` application token;
- HTTP Basic if Orion already supports it or explicitly enables it;
- future mTLS or signed request identities.

Server-side mapping:

- build an Orion subject;
- map token scopes to Git read/write/create/delete/force capabilities;
- pass subject to access policy and protected ref policy;
- never let HTTP route-level auth bypass Git operation policy.

Client-side supported auth outputs:

- no auth for public read;
- basic username/password;
- bearer token;
- provider token stored as a secret reference;
- future OAuth token refresh from application token model.

Credentials:

- never log authorization headers;
- never include tokens in exception messages;
- redact credentials from URLs;
- keep secret references separate from resolved secret values;
- avoid persisting resolved credentials in transcript fixtures.

## TLS, Redirects, And Proxies

Client-side HTTPS policy must be explicit.

TLS:

- validate certificates by default;
- use configured trust store or platform defaults;
- support custom CA only through configuration;
- insecure TLS mode must be test-only or explicitly marked unsafe.

Redirects:

- follow only configured redirect status codes;
- preserve method and body only for redirects where policy allows it;
- never forward credentials to a different host unless policy explicitly allows;
- limit redirect count;
- record redirects in diagnostics.

Proxies:

- support configured HTTP/HTTPS proxy;
- support proxy auth only through secret references;
- enforce connect and read timeouts;
- distinguish proxy auth failure from remote auth failure.

Server-side reverse proxy awareness:

- use trusted forwarded headers only from trusted proxy ranges;
- record client address safely for audit;
- do not build repository identity from untrusted host headers.

## Request Limits

Server-side limits:

- maximum URL length;
- maximum repository path length;
- maximum query parameter count;
- maximum request body bytes for receive-pack where applicable;
- maximum upload-pack request bytes;
- maximum session duration;
- maximum idle read time;
- maximum concurrent Git HTTP sessions;
- maximum response bytes when a policy needs a hard cap.

Client-side limits:

- maximum discovery response bytes;
- maximum POST response bytes before pack handoff limit;
- maximum pack bytes for single-file fallback;
- maximum redirects;
- connect timeout;
- read timeout;
- whole-operation timeout.

Limits should produce typed errors and sanitized logs.

## Streaming Model

Large packs must stream without full buffering.

Server-side:

- pass request body to receive-pack parser as a bounded stream;
- pass upload-pack response pack bytes to HTTP output stream directly;
- flush according to HTTP server behavior and side-band packet boundaries;
- propagate client disconnect as a typed cancellation;
- ensure repository locks and operation state are released on disconnect.

Client-side:

- expose response body as an input stream to protocol client primitives;
- enforce byte limits while streaming;
- allow pack parser to consume band-1 bytes incrementally;
- close response bodies promptly on failure;
- support cancellation.

Tests should verify exact byte preservation for pkt-line and pack payloads.

## Content-Type And Status Handling

Server-side response content types:

- upload-pack discovery: `application/x-git-upload-pack-advertisement`;
- receive-pack discovery: `application/x-git-receive-pack-advertisement`;
- upload-pack result: `application/x-git-upload-pack-result`;
- receive-pack result: `application/x-git-receive-pack-result`.

Client-side request content types:

- upload-pack request: `application/x-git-upload-pack-request`;
- receive-pack request: `application/x-git-receive-pack-request`.

Client-side validation:

- discovery response must have the expected advertisement type or a recognized
  error status;
- POST response must have the expected result type or a recognized error status;
- HTML login pages must map to authentication or unexpected-content failures,
  not malformed pkt-line errors;
- empty responses map to typed unexpected EOF.

## Server-Side Upload-Pack Integration

Discovery:

- resolve repository;
- authenticate subject;
- apply read and ref visibility policy;
- produce filtered advertisement through native upload-pack service;
- include protocol v2 capability advertisement when requested and supported.

POST:

- pass request stream to native upload-pack request parser;
- enforce upload-pack request limits;
- pass subject and HTTP diagnostics into operation context;
- stream pack response and side-band output;
- emit `GitUploadOrionEvent` after completion or typed failure where applicable.

The HTTP adapter should not parse wants/haves itself except for routing-level
request validation. Upload-pack service owns Git semantics.

## Server-Side Receive-Pack Integration

Discovery:

- resolve repository or creation target according to repository policy;
- authenticate subject;
- apply write/create and ref visibility policy;
- produce receive-pack advertisement through native receive-pack service.

POST:

- pass request stream to native receive-pack parser;
- enforce receive-pack body and duration limits;
- ensure pack bytes are staged through object publication service;
- stream report-status response;
- map ref rejection details to sanitized Git `ng` lines;
- emit receive/audit events after report-status outcome is known.

Repository creation:

- discovery for missing repositories must follow existing create policy;
- receive-pack may create an empty repository only when policy allows it;
- upload-pack must not create repositories.

## Client-Side Upload-Pack Transport

Outbound upload-pack client flow:

1. Build repository URL.
2. Run discovery for `git-upload-pack`.
3. Parse advertisement through protocol client primitives.
4. Select protocol version and capabilities.
5. POST upload-pack request body.
6. Validate response status and content type.
7. Return packet or side-band stream to upload-pack client.

Use cases:

- remote single-file fetch;
- mirroring fetch;
- validation of remote repository state;
- future remote config reads.

The transport must not decide what refs or objects to request. Higher-level
upload-pack client primitives own protocol behavior.

## Client-Side Receive-Pack Transport

Outbound receive-pack client flow:

1. Build repository URL.
2. Run discovery for `git-receive-pack`.
3. Parse advertisement and capabilities.
4. POST commands and pack bytes.
5. Validate response status and content type.
6. Parse report-status through protocol client primitives.
7. Return typed success or remote rejection.

Use cases:

- remote single-file push;
- GitHub/GitLab mirroring;
- future repository replication.

The transport streams the pack body. It should not build Git objects or decide
ref update policy.

## URL And Repository Normalization

Client-side URL handling:

- accept `https://host/path/repo.git`;
- accept `http://` only when configuration allows it;
- preserve existing `.git` suffix in outbound URLs;
- avoid double slash issues when appending service paths;
- reject unsupported schemes;
- reject credentials embedded in URLs unless migration compatibility explicitly
  allows and immediately redacts them.

Server-side route normalization:

- map `/r/name.git` and `/r/name` only if configured;
- avoid ambiguity with nested repository names;
- canonicalize repository id before authorization;
- keep original path in diagnostics only after sanitization.

## Error Model

Use typed errors:

- invalid repository path;
- unsupported service;
- invalid method;
- missing service query parameter;
- invalid service query parameter;
- invalid content type;
- authentication required;
- authentication failed;
- authorization denied;
- repository missing;
- repository create denied;
- unsupported protocol version;
- malformed `Git-Protocol` header;
- request body too large;
- response body too large;
- timeout;
- client disconnected;
- TLS validation failed;
- redirect denied;
- proxy failure;
- unexpected HTTP status;
- unexpected content type;
- malformed discovery advertisement;
- malformed POST response;
- remote Git error packet.

Error diagnostics should include:

- direction: inbound or outbound;
- service: upload-pack or receive-pack;
- phase: discovery or post;
- HTTP method;
- sanitized host or repository id;
- status code when available;
- retryability;
- request id.

Do not include:

- authorization headers;
- password, token, or secret values;
- object contents;
- hidden ref names in user-visible errors.

## Observability

Server metrics:

- discovery request count by service;
- POST request count by service;
- authenticated subject type;
- HTTP status count;
- Git service success/failure count;
- request body bytes;
- response body bytes;
- duration by phase;
- client disconnect count;
- auth failure count;
- content-type rejection count.

Client metrics:

- discovery duration;
- POST duration;
- bytes sent;
- bytes received;
- redirect count;
- TLS failure count;
- auth failure count;
- unexpected status count;
- remote reject count;
- timeout count.

Logs should link HTTP access records to Git operation ids when available.

## Test Fixtures

Build HTTP-level fixtures around the protocol core fixtures:

- fake HTTP server for discovery and POST;
- fake HTTP client for server-side route tests;
- scripted upload-pack discovery advertisement;
- scripted receive-pack discovery advertisement;
- scripted side-band response;
- scripted report-status response;
- HTML login page fixture;
- redirect fixture;
- TLS failure fixture where practical;
- proxy failure fixture where practical.

Tests should avoid external network access by default.

Real provider compatibility tests:

- GitHub upload-pack discovery and fetch;
- GitLab upload-pack discovery and fetch;
- receive-pack dry-run or disposable test repository;
- token auth behavior;
- redirect behavior.

These should be integration tests behind explicit configuration, not routine
unit tests.

## Phased Plan

Phase 1: Shared HTTP constants and error model.

- Define service paths, query names, content types, and typed errors.
- Add sanitization helpers for URLs and headers.
- Add tests for redaction and content-type constants.

Phase 2: Server route parser.

- Parse `/r/<repo>/info/refs?service=...`.
- Parse `/r/<repo>/git-upload-pack` and `/r/<repo>/git-receive-pack`.
- Reject invalid paths, methods, services, and content types.

Phase 3: Server discovery adapter.

- Wire discovery requests to native upload-pack and receive-pack advertisement
  services.
- Add service announcement framing through the protocol core.
- Add auth and visibility policy context.

Phase 4: Server POST adapter.

- Stream request body into native upload-pack or receive-pack service.
- Stream response body with correct content type.
- Add timeout, body size, and disconnect handling.

Phase 5: Client discovery transport.

- Implement outbound GET discovery for upload-pack and receive-pack.
- Validate status and content type.
- Parse advertisement through protocol client primitives.

Phase 6: Client POST transport.

- Implement outbound POST framing for upload-pack and receive-pack.
- Stream request and response bodies.
- Map HTTP and content errors to typed transport errors.

Phase 7: Authentication hooks.

- Add bearer token and basic auth providers.
- Integrate application token or credential-reference resolution.
- Verify redaction in errors, logs, and fixtures.

Phase 8: TLS, redirects, and proxy policy.

- Add explicit TLS trust policy.
- Add redirect policy with credential forwarding safeguards.
- Add proxy configuration and timeout behavior.

Phase 9: Upload-pack integration tests.

- Run remote single-file fetch through smart HTTP scripted server.
- Run native server-side upload-pack route tests with scripted HTTP requests.
- Verify protocol v2 `Git-Protocol` header behavior.

Phase 10: Receive-pack integration tests.

- Run remote single-file push through smart HTTP scripted server.
- Run native server-side receive-pack route tests with scripted HTTP requests.
- Verify report-status and remote reject mapping.

Phase 11: Real Git CLI compatibility.

- Use local Git CLI against Orion smart HTTP routes where practical.
- Test clone/fetch/push against disposable repositories.
- Keep tests gated if they require ports or external processes.

Phase 12: Provider compatibility.

- Add optional GitHub/GitLab compatibility tests behind explicit credentials and
  repository configuration.
- Keep these outside routine local test workflows.

## Verification

Server route parsing:

- valid upload-pack discovery route resolves service and repository;
- valid receive-pack discovery route resolves service and repository;
- valid upload-pack POST route resolves service and repository;
- invalid method is rejected;
- invalid service query is rejected;
- invalid content type is rejected;
- path traversal is rejected;
- `.git` suffix handling follows repository policy.

Server discovery:

- response content type is correct;
- response starts with service announcement and flush;
- hidden refs are filtered;
- unauthorized discovery is sanitized;
- protocol v2 header selects v2 advertisement when supported.

Server POST:

- upload-pack request stream reaches native upload-pack service;
- receive-pack request stream reaches native receive-pack service;
- response bytes are not buffered fully for large packs;
- client disconnect releases operation resources;
- body limit failures are typed and logged.

Client discovery:

- upload-pack discovery validates content type;
- receive-pack discovery validates content type;
- HTML login page maps to authentication or unexpected-content failure;
- redirect policy is enforced;
- bearer and basic auth headers are sent only when configured.

Client POST:

- upload-pack POST streams request and response;
- receive-pack POST streams commands and pack bytes;
- unexpected HTTP status maps to typed failure;
- unexpected content type maps to typed failure;
- side-band fatal and report-status errors remain service-level errors, not raw
  HTTP errors.

Security:

- credentials are redacted from logs and exceptions;
- credentials are not forwarded to a different redirect host by default;
- TLS validation is enabled by default;
- insecure TLS mode requires explicit unsafe configuration;
- repository existence is not leaked when policy says to hide it.

Compatibility:

- Git CLI can run discovery against Orion route;
- Git CLI can fetch from native upload-pack route after native service is ready;
- Git CLI can push to native receive-pack route after native service is ready;
- outbound smart HTTP transport can talk to scripted provider-like fixtures;
- production code has no JGit dependency.

## Rollout

Start with server route parsing and discovery because it is easy to validate
without streaming large packs.

Add server POST only after native upload-pack and receive-pack services expose
stream-based boundaries.

Add outbound client discovery and POST next, initially against scripted fixtures.

Use smart HTTP(S) as the first production outbound transport for remote
single-file fetch/push and mirroring after scripted transport behavior is stable.

Keep provider compatibility tests optional and credential-gated.

Keep native smart HTTP server routes behind feature flags until upload-pack,
receive-pack, access policy, and audit behavior match the existing JGit-backed
routes.

## Open Questions

Should server-side Git smart HTTP live in `net/http-core`, a Git transport
module, or a small adapter module between them?

Should `/r/<repo>.git` and `/r/<repo>` both be supported, or should Orion choose
one canonical route and redirect the other?

Should missing repositories return `404` or sanitized `403` when the subject
does not have create/read permission?

Which HTTP client should outbound smart HTTP use so timeouts, proxy, TLS, and
streaming behavior are consistent with the rest of Orion?

Should outbound provider integration prefer bearer tokens only, or keep basic
auth for GitHub/GitLab token compatibility?

Should protocol v0/v1 smart HTTP upload-pack support be implemented before
server-side route rollout, or can Orion require protocol v2 during the native
backend pilot?

How should reverse proxy trusted headers be configured for audit source address?

## Acceptance Criteria

Orion has a JGit-free smart HTTP server adapter that can route discovery and
POST requests to native upload-pack and receive-pack services with correct
headers, content types, authentication, limits, and streaming behavior.

Orion has a JGit-free smart HTTP(S) client transport that can perform upload-pack
and receive-pack discovery and POST framing for native remote operations.

Credentials, hidden refs, and repository existence are sanitized according to
policy.

TLS, redirect, proxy, timeout, and request size behavior are explicit and tested.

The adapter uses the shared Git wire protocol core rather than duplicating
pkt-line, capability, side-band, or report-status parsing.

Routine tests require no external network access, while provider compatibility
tests are available behind explicit configuration.

# Secret Reference and Credential Management

## Goal

Add a shared secret-reference and credential-management layer for Orion.

This layer should let Git transports, resource storage, mirroring, S3 backends,
OAuth/OIDC, dynamic DNS providers, and administrative APIs refer to credentials
without storing raw secret values in repository metadata, route config, mirror
config, logs, events, or diagnostic reports.

The first useful version should support safe references to environment variables,
files, inline test-only content, and a local encrypted secret store. Later
versions can add external secret providers such as cloud secret managers, Vault,
or OS keychains.

## Current State

The embedded resource-reference contract defines safe display
rules for nested references such as `file:env:ORION_CONFIG` and warns against
printing expanded environment values, inline secret content, keystore passwords,
or private key content.

The embedded resource-resolution model lists secret
reference handling as a common resolver helper, but does not define the secret
store, credential record model, rotation behavior, access policy, or audit
rules.

[The key-material task](../01_unified-key-material-bootstrap/TASK.md) covers Orion-owned
private keys and certificates: server signing keys, HTTPS keys, ACME account
keys, SSH host keys, and CA material. It does not define generic provider
tokens, S3 credentials, SSH client private keys, known-host entries, proxy
passwords, or OAuth client secrets.

[The application-token model](../14_application-tokens/01_model-and-storage.md) covers inbound
application tokens used to authenticate clients to Orion. It is not a generic
outbound credential store for Orion to talk to remote providers.

The integrated Smart HTTP transport and
the integrated SSH transport both need credential
references for bearer tokens, basic auth, private keys, passphrases, known-host
entries, proxy authentication, and provider-specific tokens.

[The provider mirroring task](../07_external-git-repository-sync/03_github-commit-replication.md) explicitly says
mirror config should use credential references and asks where provider
credentials should live before secret management exists.

[Externalized repository storage](../11_git/06_externalized-repository-storage.md) says S3 credentials
come from the location or secret resolver and must not be stored in repository
metadata or event records.

Today several code paths still rely on direct files, environment variables,
JGit-specific credential callbacks, or ad hoc configuration fields. Native Git
and native storage need a common boundary.

## Non-Goals

Do not replace the key material service for Orion-owned private keys and
certificates. This plan can reference key material aliases, but it does not own
server signing keys, HTTPS keys, ACME account keys, SSH host keys, or CA issuer
keys.

Do not replace application-token storage. Application tokens authenticate
clients to Orion; secret references mostly provide credentials Orion uses when
calling other systems.

Do not add OAuth/OIDC login flows here. Store or resolve provider client secrets
only as credentials consumed by the OAuth plan.

Do not implement every external secret provider in the first version.

Do not expose raw secret readback through ordinary admin APIs.

Do not store raw secrets in Git repository metadata, mirror config, dynamic
domain config, resource addresses, audit records, logs, or exception messages.

Do not use reversible encryption as a substitute for access control. Decryption
must require explicit service permission and audit.

## Terminology

Secret value:

- raw sensitive bytes or text;
- examples: provider token, password, SSH private key, passphrase, access key,
  proxy password, OAuth client secret, known-host file content when treated as
  sensitive operational config.

Secret reference:

- non-secret identifier that tells Orion where and how to resolve a secret;
- examples: `env:NAME`, `file:/path/token`, `secret:git/github-token`,
  `keystore:ssh-client-key`, `content:base64,<redacted-test-only>`.

Credential:

- structured use of one or more secret references;
- examples: HTTP bearer credential, HTTP basic credential, SSH private-key
  credential, S3 credential, proxy credential.

Safe display:

- string safe for logs, errors, API responses, and audit;
- never includes raw secret value or resolved file content.

Resolution context:

- caller, purpose, repository, operation id, bootstrap phase, and allowed secret
  source policy used while resolving a reference.

## Reference Schemes

Support a small set of schemes with explicit bootstrap behavior.

`env:NAME`:

- read from process environment;
- available during bootstrap;
- safe display is `env:NAME`;
- missing environment variable is a typed error;
- value is never logged.

`file:/path`:

- read from local file;
- available only when file access is allowed for the current bootstrap phase;
- safe display is `file:/path` or a redacted configured label;
- path must be normalized and checked for traversal where relative paths are
  allowed;
- file content is never logged.

`content:<value>` and `content:base64,<value>`:

- inline value;
- test-only or explicitly unsafe bootstrap escape hatch;
- safe display is `content:<redacted>` or `content:base64,<redacted>`;
- disabled by default for production configuration unless explicitly allowed.

`secret:<name>`:

- lookup in Orion's managed secret store;
- unavailable before the secret store is initialized;
- safe display is `secret:<name>`;
- supports versioning and metadata.

`keystore:<alias>`:

- lookup a key or certificate alias through the key material service;
- used only for key material that belongs there;
- safe display is `keystore:<alias>`;
- not a generic text secret store unless the project explicitly enables
  `SecretKeyEntry` use.

Future schemes:

- `vault:<path>`;
- `aws-secrets:<id>`;
- `gcp-secret:<id>`;
- `azure-keyvault:<id>`;
- `os-keychain:<id>`.

Unknown schemes should fail with typed unsupported-reference errors.

## Secret Store Model

Introduce a generic managed secret store for non-key-material credentials.

Candidate value objects:

- `SecretReference`;
- `SecretId`;
- `SecretVersionId`;
- `SecretRecord`;
- `SecretVersionRecord`;
- `SecretKind`;
- `SecretPurpose`;
- `SecretAccessPolicy`;
- `SecretResolutionRequest`;
- `SecretResolutionResult`;
- `ResolvedSecret`;
- `SecretSafeDisplay`;
- `SecretAuditRecord`;

Secret record metadata:

- stable secret id;
- display name;
- kind;
- purpose tags;
- owner subject;
- created timestamp;
- updated timestamp;
- active version id;
- disabled flag;
- rotation policy;
- allowed consumers;
- optional expiry;
- safe description;
- schema version.

Secret version metadata:

- version id;
- encrypted payload reference;
- created timestamp;
- created by subject;
- checksum or authenticated encryption tag;
- payload encoding;
- previous version id;
- activation state;
- expiry timestamp;
- deletion state.

The raw secret payload must be encrypted at rest when stored by Orion.

## Secret Kinds

Define broad kinds first, not provider-specific classes for every system.

Kinds:

- `TEXT`;
- `BINARY`;
- `USERNAME_PASSWORD`;
- `BEARER_TOKEN`;
- `SSH_PRIVATE_KEY`;
- `SSH_KNOWN_HOSTS`;
- `S3_ACCESS_KEY`;
- `HTTP_PROXY_CREDENTIAL`;
- `OAUTH_CLIENT_SECRET`;
- `PROVIDER_TOKEN`;

Each kind should define:

- expected payload shape;
- validation rules;
- safe display fields;
- whether raw readback is ever allowed;
- common consumers.

Avoid overfitting to GitHub, GitLab, AWS, or Cloudflare in the core secret
model. Provider adapters can impose additional validation.

## Credential Models

Credentials should be structured wrappers around one or more secret references.

HTTP bearer credential:

- username optional;
- bearer token secret reference;
- header policy;
- allowed hosts or URL prefixes.

HTTP basic credential:

- username literal or secret reference;
- password secret reference;
- allowed hosts or URL prefixes.

SSH private-key credential:

- username;
- private key secret reference or key material alias;
- passphrase secret reference optional;
- known-hosts reference;
- allowed hosts;
- optional pinned fingerprint.

S3 credential:

- access key id reference or literal according to policy;
- secret access key reference;
- session token reference optional;
- region;
- endpoint allowlist;
- role/session metadata optional.

Proxy credential:

- proxy URL or id;
- username reference optional;
- password reference;
- allowed target hosts optional.

OAuth/OIDC provider credential:

- client id literal or reference;
- client secret reference;
- issuer or provider id;
- redirect URI binding.

The credential model should expose safe diagnostics without resolving secrets.

## Resolution API

Add a service boundary:

```text
SecretResolver.resolve(reference, context) -> ResolvedSecret
CredentialResolver.resolve(credentialRef, context) -> ResolvedCredential
```

Resolution context includes:

- caller component;
- operation id;
- authenticated subject or internal service actor;
- purpose;
- bootstrap phase;
- repository id or resource id when applicable;
- allowed reference schemes;
- maximum nested reference depth;
- whether inline content is allowed;
- whether raw bytes may leave the resolver.

`ResolvedSecret` should:

- hold bytes or chars in memory for the shortest practical time;
- implement close/clear when possible;
- expose safe display;
- record source scheme and version id;
- avoid accidental `toString()` leakage.

The resolver should not cache raw secret values by default. If caching is later
needed, it must be bounded, purpose-scoped, and clearable.

## Bootstrap Phases

Secret resolution must be phase-aware.

`PRE_CONFIGURATION`:

- only literal config, `env:`, and allowed `file:` references;
- no managed secret store;
- no runtime repository storage.

`CONFIGURATION_LOAD`:

- configuration files may use `env:` and `file:`;
- managed store still unavailable unless it has an independent bootstrap root.

`ACL_BOOTSTRAP`:

- ACL storage credentials can use environment or file references;
- do not require Orion network transports to resolve secrets;
- do not depend on ACL grants that are not loaded yet.

`RUNTIME`:

- managed `secret:` store is available;
- authorization policy and audit are active;
- remote Git, S3, DNS, OAuth, and mirroring can resolve credentials.

The resolver must fail fast when a reference is unavailable in the current phase.

## Storage Backends

Start with a local encrypted secret store.

Local layout example:

```text
<baseDir>/secrets/
  manifest.json
  records/<secret-id>.json
  payloads/<secret-id>/<version-id>.bin
```

Requirements:

- explicit schema version;
- atomic writes through temp files and rename;
- per-payload authenticated encryption;
- manifest checksum;
- startup validation;
- backup-friendly files;
- no raw secret values in metadata JSON.

Encryption key options:

- derive from configured master secret reference;
- use key material service if it exposes a suitable secret-encryption key;
- use external KMS later;
- fail startup if encryption material is unavailable.

S3 or remote secret storage can be added later after the local encrypted store is
stable.

## Encryption And Integrity

At-rest payload encryption should use authenticated encryption.

Requirements:

- random nonce per version;
- authenticated metadata binding where practical;
- encryption key id recorded in metadata;
- clear error when key is unavailable;
- no partial payload publication;
- checksum or authentication tag validation before use;
- key rotation support later.

Do not invent cryptographic primitives. Use standard Java crypto APIs or an
existing project-approved helper.

If encryption support is not ready for the first phase, the managed `secret:`
store should remain read-only or disabled. Do not ship plaintext managed secret
storage as the default.

## Access Control

Secret records need access policy.

Actions:

- create secret;
- list secret metadata;
- read safe metadata;
- resolve secret for a purpose;
- rotate secret;
- disable secret;
- delete secret metadata;
- purge secret payload after retention.

Resolution should require both:

- subject or internal service permission;
- purpose match for the requested consumer.

Examples:

- Git mirror worker can resolve only mirror credentials assigned to that mirror;
- S3 repository backend can resolve only credentials for its configured storage;
- smart HTTP outbound transport can resolve only credentials passed by the
  higher-level remote operation;
- admin API can list metadata but not raw secret values.

Access failures must not reveal raw secret values and should avoid confirming
secret existence when policy requires hiding it.

## Audit

Audit secret lifecycle and resolution without logging raw values.

Events:

- secret created;
- secret version added;
- secret activated;
- secret rotated;
- secret disabled;
- secret metadata updated;
- secret resolution succeeded;
- secret resolution denied;
- secret resolution failed;
- secret deleted or purged.

Resolution audit should include:

- secret id;
- version id when available;
- purpose;
- caller component;
- subject or service actor;
- operation id;
- repository or resource id when applicable;
- result;
- failure reason.

Do not include:

- raw secret payload;
- decrypted bytes;
- token prefixes beyond safe configured preview, if any;
- private key paths when those paths are themselves secret references.

## Redaction

Provide shared redaction helpers.

Redact:

- authorization headers;
- token query parameters;
- embedded URL credentials;
- secret reference resolved values;
- private key contents;
- passphrases;
- S3 signed URLs;
- OAuth client secrets;
- proxy credentials;
- inline `content:` payloads.

Safe displays:

- `env:NAME`;
- `file:/path/to/secret`;
- `secret:provider/github-token`;
- `keystore:ssh-host-ed25519`;
- `content:<redacted>`;
- `https://example.com/repo.git` with userinfo removed.

Every credential class should have:

- unsafe raw values inaccessible through `toString()`;
- explicit safe display method;
- tests proving logs and exception messages do not contain secret values.

## Rotation

Support versioned secrets from the beginning.

Rotation flow:

1. Create new secret version in staged state.
2. Validate the version where possible.
3. Activate the new version atomically.
4. Keep old version available for configured overlap if consumers need it.
5. Mark old version retired.
6. Purge retired payload after retention if policy allows.

Consumers should resolve active version at operation start and pin it for the
duration of the operation.

Do not switch credentials mid-upload, mid-push, or mid-receive operation.

## Validation

Secret kind validators should be optional but useful.

Examples:

- bearer token is non-empty text;
- username/password has both fields when required;
- SSH private key parses as a private key without exposing content;
- known-hosts content parses into at least one host key;
- S3 access key credential has required parts;
- OAuth client secret is non-empty;
- URL allowlist is valid.

Validation should avoid contacting external systems unless the caller explicitly
requests a connectivity test.

## Admin API

Add admin operations after the model and store are stable.

Possible endpoints:

- `POST /api/admin/secrets`;
- `GET /api/admin/secrets`;
- `GET /api/admin/secrets/{id}`;
- `POST /api/admin/secrets/{id}/versions`;
- `POST /api/admin/secrets/{id}/activate`;
- `POST /api/admin/secrets/{id}/disable`;
- `DELETE /api/admin/secrets/{id}`;

Responses must never include raw payloads.

Create and rotate requests may accept a raw secret value in the request body, but
the response should return only metadata and safe display.

If UI support is added later, it should mark fields as secret and avoid
pre-filling sensitive values.

## CLI And Configuration

Add non-interactive administration paths:

- import secret from stdin;
- import secret from file;
- import known-hosts entry;
- import SSH private key;
- rotate secret;
- list metadata;
- validate reference.

Configuration should use references, not raw values:

```yaml
remote:
  credential: secret:git/github-mirror
```

Avoid supporting raw provider tokens directly in long-lived config except for
explicit test/dev overrides.

## Integration With Resource References

Resource reference resolution should call the secret resolver only when a scheme
or parameter is declared secret.

Examples:

```text
s3://bucket/key?accessKey=secret:s3/access-key&secretKey=secret:s3/secret-key
git+https://example/repo.git?credential=secret:git/github-token
git+ssh://git@example/repo.git?credential=secret:ssh/deploy-key
```

Rules:

- nested reference depth is bounded;
- secret resolution depends on bootstrap phase;
- safe display preserves original reference;
- incompatible resolved schemes fail clearly;
- query parameters containing secret references are redacted in diagnostics.

## Integration With Smart HTTP Git

Outbound smart HTTP credentials:

- bearer token from `secret:`;
- basic auth password from `secret:`;
- provider token from `secret:`;
- proxy auth from `secret:`;
- client certificate alias from key material if mTLS is later supported.

Server-side smart HTTP:

- inbound bearer application tokens use application-token auth, not generic
  secret resolution;
- route config should reference TLS keys through key material service;
- errors must redact auth headers and URL userinfo.

Redirect policy must not forward resolved credentials across hosts unless the
credential record explicitly allows it.

## Integration With SSH Git

Outbound SSH credentials:

- private key from `secret:` or key material alias depending on policy;
- passphrase from `secret:`;
- known-hosts from `secret:` or file;
- pinned fingerprint stored as metadata or trusted certificate entry.

Server-side SSH:

- host keys come from key material service, not generic secret store;
- user public keys remain in ACL or a dedicated identity model;
- `issue-token` uses application-token storage, not generic secret storage.

Private key support should be explicit. Orion should not become a general
user-private-key vault without a product decision.

## Integration With S3 Backends

S3 repository and pack storage credentials:

- access key id may be literal or secret reference according to policy;
- secret access key must be a secret reference;
- session token should be a secret reference;
- endpoint and bucket are not secrets but may still be sensitive operational
  data;
- signed URLs must be redacted.

Credential resolution should happen at operation start and be pinned for the
operation.

Rotation should allow overlapping active credentials where S3 provider policy
requires it.

## Integration With Mirroring

Mirror config should store credential references only.

Mirror worker behavior:

- resolve credential at sync start;
- pin credential version for the sync attempt;
- redact remote URLs and auth headers in retry records;
- record credential id and version id in internal diagnostics;
- never expose raw provider tokens through admin mirror APIs.

Credential denial should mark mirror sync as configuration/auth failure, not as
a Git protocol failure.

## Integration With OAuth/OIDC

OAuth provider configuration should reference client secrets:

- `clientId` may be literal or secret reference depending on policy;
- `clientSecret` must be a secret reference;
- provider metadata is not secret;
- refresh tokens, if stored later, should use either token-specific storage or
  this secret store with purpose-specific access policy.

OAuth callback errors must not print provider secrets.

## Integration With Dynamic DNS And Domains

DNS provider credentials should use secret references:

- API token;
- account id if sensitive by policy;
- zone token;
- webhook signing secret.

Domain allocation records should store provider ids and credential references,
not raw provider credentials.

## Error Model

Use typed errors:

- unsupported reference scheme;
- malformed secret reference;
- missing environment variable;
- file secret not found;
- file secret unreadable;
- inline content disabled;
- managed secret store unavailable;
- secret not found;
- secret disabled;
- secret expired;
- secret version not active;
- purpose not allowed;
- access denied;
- decryption key unavailable;
- decrypt/authentication failure;
- payload validation failed;
- nested reference depth exceeded;
- bootstrap phase disallows source;
- external provider unavailable;
- raw readback denied.

Errors should include:

- safe display;
- purpose;
- caller component;
- bootstrap phase;
- retryability;
- operation id.

Errors must not include raw values.

## Observability

Metrics:

- secret resolution count by source scheme;
- resolution failure count by reason;
- managed store load duration;
- managed store write duration;
- active secret count by kind;
- rotation count;
- disabled secret count;
- access denied count;
- decryption failure count;
- redaction test failure count in test diagnostics only.

Logs:

- store startup and validation result;
- secret lifecycle changes;
- denied resolution with safe display;
- decryption or corruption failures without payload;
- rotation activation.

Do not log every successful resolution at high volume by default unless audit
policy requires it.

## Phased Plan

Phase 1: Secret reference model.

- Add `SecretReference`, safe display, parser, and scheme model.
- Support `env:`, `file:`, `content:`, `secret:`, and `keystore:` references as
  syntax.
- Add redaction tests for all schemes.

Phase 2: Resolution context.

- Add purpose, caller, bootstrap phase, operation id, and allowed-source policy.
- Enforce source availability by phase.
- Add nested depth limit.

Phase 3: Environment and file resolvers.

- Resolve `env:` and `file:` with safe diagnostics.
- Enforce missing/unreadable errors.
- Keep raw values out of `toString()`.

Phase 4: Content resolver for tests.

- Implement `content:` and `content:base64,` only behind explicit test/dev
  policy.
- Add production-disabled tests.

Phase 5: Credential value objects.

- Add HTTP bearer, HTTP basic, SSH private-key, known-hosts, S3, proxy, OAuth,
  and provider-token credential models.
- Add validation and safe display.

Phase 6: Local encrypted secret store.

- Define manifest, record, version, and payload formats.
- Add authenticated encryption.
- Add atomic write and startup validation.

Phase 7: Managed `secret:` resolver.

- Resolve active secret versions from local encrypted store.
- Enforce access policy and purpose.
- Add audit records for resolution.

Phase 8: Admin import and rotation.

- Add non-raw-listing admin API or CLI.
- Create, rotate, disable, and list metadata.
- Verify raw values are never returned after create/import.

Phase 9: Smart HTTP integration.

- Resolve outbound bearer/basic/proxy credentials through credential resolver.
- Redact URL userinfo and auth headers.
- Add redirect credential-forwarding safeguards.

Phase 10: SSH integration.

- Resolve private key, passphrase, known-hosts, and pinned fingerprints.
- Add strict known-host fixtures.
- Keep server host keys in key material service.

Phase 11: S3 integration.

- Resolve access keys and session tokens through credentials.
- Redact signed URLs and storage errors.
- Pin credential version per operation.

Phase 12: Mirroring and provider integration.

- Store only credential references in mirror config.
- Resolve provider credentials in mirror worker.
- Add retry records that contain safe credential ids only.

Phase 13: External provider extension points.

- Add interfaces for Vault, cloud secret managers, or OS keychain.
- Keep them disabled unless configured.

## Verification

Reference parsing:

- `env:NAME` parses and safe display is exact;
- `file:/path/token` parses and safe display omits content;
- `content:secret` displays as `content:<redacted>`;
- `secret:git/github-token` parses;
- unknown scheme is rejected.

Resolution:

- environment value resolves when present;
- missing environment variable fails with typed error;
- file value resolves when allowed;
- file value is denied in disallowed bootstrap phase;
- inline content is denied in production policy;
- nested references obey max depth.

Managed store:

- create secret writes encrypted payload only;
- startup reload reads metadata without raw payload logging;
- active version resolves;
- disabled secret fails;
- expired secret fails;
- corrupted payload fails authentication;
- interrupted write does not replace previous active version.

Access policy:

- unauthorized subject cannot resolve secret;
- wrong purpose is denied;
- mirror worker resolves only assigned mirror credential;
- admin metadata list omits raw values;
- raw readback is denied.

Rotation:

- new version can be staged;
- activation switches future resolutions;
- operation pins old version until completion;
- retired version is purged only after retention.

Credential models:

- HTTP bearer auth header is built but never logged;
- HTTP basic password is redacted;
- SSH private key and passphrase resolve for client adapter;
- known-host content validates;
- S3 credential resolves required fields;
- proxy credential is not forwarded outside allowed scope.

Integration:

- smart HTTP outbound uses credential resolver;
- SSH outbound uses credential resolver and strict known-host policy;
- S3 backend resolves credentials without storing them in repository metadata;
- mirror config stores credential reference only;
- OAuth provider config stores client secret reference only;
- logs and exceptions do not contain known test secret values.

## Rollout

Start with reference parsing, safe display, and `env:`/`file:` resolution because
those unblock bootstrap and resource addressing without committing to a managed
secret store format.

Add credential value objects before integrating transports so HTTP, SSH, S3, and
mirroring use the same redaction and validation rules.

Enable local encrypted `secret:` storage only after encryption, atomic
publication, and access policy tests pass.

Wire smart HTTP and SSH outbound transports to the credential resolver behind
feature flags.

Move mirror and S3 configs from ad hoc credential fields to secret references
after compatibility tests pass.

Keep external secret providers as later optional adapters.

## Open Questions

Should the first managed secret store use the key material service for its
encryption key, or a separate master secret reference?

Should `SecretKeyEntry` in the key material keystore be used for generic secret
encryption keys, or should the secret store own a separate key file/provider?

Should Orion allow storing user SSH private keys, or require caller-provided
keys/agents and only store deploy keys explicitly created for automation?

Should secret metadata live in local files first, repository-backed storage, or
the same storage backend used for ACL/application tokens?

Should admin APIs allow raw secret export for backup, or should backups operate
only at encrypted store-file level?

How should secret access policy relate to ACL grants: named actions, resource
grants, or purpose-specific internal allowlists?

Should known-host entries be treated as secrets or as trusted public metadata
with integrity requirements?

## Acceptance Criteria

Orion has a shared secret-reference parser, safe display model, resolution
context, and redaction helpers.

Environment and file secret references can be resolved safely in supported
bootstrap phases.

Managed `secret:` records are encrypted at rest, versioned, access-controlled,
audited, and never returned through ordinary metadata APIs.

HTTP, SSH, S3, mirroring, OAuth, and dynamic provider plans can reference
credentials through the same credential model.

Logs, errors, audit records, event records, repository metadata, and config
reports contain safe references only, not raw secret values.

---

## Resource Reference and Address Model

### Goal

Replace the current `ResourceLocation` concept with clearer terminology and a
model that supports nested resource schemes.

The existing name suggests a simple URI or path, but Orion uses these values to
select backend implementations and, increasingly, to resolve indirection through
environment variables, inline content, object storage, Git, and local files.

The new model should distinguish:

- a raw user/config expression that may contain indirection;
- a resolved concrete address that can be passed to a backend;
- the normalized scheme used for backend selection.

### Current Discussion Status - 2026-05-15

The design has moved away from colon-only nested references such as
`file:env:ORION_CONFIG` as the primary syntax. That form is hard to read once
defaults, paths, document dereferencing, and nested sources are combined. The
sections below still describe the earlier direction and should be treated as
historical context until this plan is rewritten.

The current syntax and implementation contract are now documented in
`core/resource-addressing/README.md`.

The current preferred direction is shell-style interpolation:

```text
$ORION_ROOT
${ORION_ROOT}
${ORION_ROOT:-orion_root}
$ORION_ROOT/orion.xml
$ORION_ROOT/$PATH_TO_CONFIG
${ORION_ROOT:-orion_root}/orion.xml
${bootstrap.baseDir}/orion.xml
```

Rules currently agreed:

- `$NAME` and `${NAME}` read an immutable scalar value.
- Four Bash-compatible default/required operators are supported for every
  reference type, not only environment variables:
  - `${NAME:-default}` uses `default` when `NAME` is unset or blank.
  - `${NAME-default}` uses `default` only when `NAME` is unset; blank is a valid
    value.
  - `${NAME:?message}` fails with `message` when `NAME` is unset or blank.
  - `${NAME?message}` fails with `message` only when `NAME` is unset; blank is a
    valid value.
- Blank means `String.isBlank()` after the referenced value has been converted
  to a string.
- Assignment-style operators such as `${NAME:=default}` and `${NAME=default}`
  are intentionally not supported because resolution is immutable.
- `$NAME` is only a shorthand for simple names matching
  `[A-Za-z_][A-Za-z0-9_]*`. It has no defaults, no dotted names, and no nested
  syntax. Use `${...}` for every complex reference.
- Names without dots are environment references by default.
- Dotted names such as `${bootstrap.baseDir}` refer to the current resolution
  context or configuration model.
- References may point back into the same configuration model, so resolution
  must detect direct and indirect cycles.
- Interpolation itself is read-only and immutable. Writable capabilities are
  selected later by the call site.

The call site should ask for a capability, not always for a low-level Java type.
Examples:

```java
resolver.resolve(value, ResourceContent.class)
resolver.resolve(value, Path.class)
resolver.resolve(value, ExternalDirectory.class)
resolver.resolve(value, RepositoryStorage.class)
```

This keeps address schemes from encoding operations. For example, `file:` should
mean "local file resource", not always "read file now". If the call site asks for
`Path`, it receives a path. If it asks for `ResourceContent`, the file resource
is read as an immutable content snapshot. If the call site does not yet know the
capability, it should first resolve to a neutral resolved reference/address and
then dispatch by scheme and usage.

The resource-addressing module should provide lightweight built-in resolvers for
the common schemes needed by this model: `file:`, `http:`, `https:`, `s3:`,
`git:`, and remote Git schemes such as `git+ssh:`, `git+http:`, and
`git+https:`. These resolvers should avoid depending on the heavier runtime
storage or transport implementations. They exist to parse addresses, perform
small immutable reads when a call site requests `ResourceContent`, and keep
reference resolution behavior deterministic and testable.

Resolver selection must be extensible. Other modules should be able to
contribute scheme/capability resolvers through an explicit registry or provider
interface without changing the core parser. For example, the Orion runtime
module can register a resolver for `local:` so `local:orion` is interpreted as a
reference to an internal local `GitRepository` in Orion's configured repository
storage, while the core resource-addressing module can still treat `local:` as
unknown unless that resolver is installed.

Inline Kubernetes-style config data should be expressible explicitly. The
current candidate is `content:` for literal content, while address schemes such
as `file:`, `s3:`, and `git+...` still identify resources that can produce
content:

```text
content:${ORION_CONFIG_DATA}
file:${ORION_CONFIG_PATH:-config.yml}
s3://${CONFIG_BUCKET}/orion.yml
```

Document dereferencing is still open. The requirement is to parse YAML, TOML, or
XML content and read a property/path from it. Current examples:

```text
${yaml:${ORION_CONFIG_DATA}/bootstrap/baseDir}
${yaml:$ORION_CONFIG_DATA/bootstrap/baseDir}
${yaml:$ORION_CONFIG_PATH/bootstrap/baseDir}
${yaml:${ORION_CONFIG_PATH:-config.yml}/bootstrap/baseDir}
${yaml:${bootstrap.configPath}/bootstrap/baseDir}
${xml:${ORION_ACL_PATH:-orion.xml}/accessControl/users/root}
```

The earlier `#` separator idea, for example
`${xml:file:${ORION_ACL_PATH:-orion.xml}#/accessControl/users/root}`, is not
settled. The current preference is to rely on nested `${...}` boundaries where
needed instead of adding another separator. One unresolved point is how a schema
reference such as `yaml:` or `xml:` decides whether its source is inline content,
a local path, or another URL/address. The likely direction is:

- parse inline content directly when the source is known content;
- read `file:`, `s3:`, `git+...`, and similar address schemes as
  `ResourceContent`;
- allow `$NAME`, `${NAME}`, `${NAME:-default}`, and `${context.name}` as schema
  sources, but parse them as AST nodes rather than concatenating strings first;
- require a schema source to be either a nested `$...`/`${...}` reference or an
  explicit address scheme. Plain inline text in `yaml:`/`xml:` without a nested
  reference is not part of the current design;
- after resolving the schema source, parse it directly if it resolves to
  `ResourceContent` or obvious inline scalar content;
- if the source resolves to `Path` or a valid address/URI, read it as
  `ResourceContent` and then parse it;
- if a source looks like a path or URI but cannot be read, fail rather than
  silently treating the path string as inline document content.

The schema reference must therefore be parsed as a structured node, for example:

```text
DocumentReference(
  schema = yaml,
  source = $ORION_CONFIG_PATH,
  pointer = /bootstrap/baseDir
)
```

The resolver should not first interpolate everything into one flat string and
then try to rediscover where the document source ended and the document pointer
began.

Before implementation, this section should be turned into a revised syntax
specification and test matrix for the `core/resource-addressing` module.

### Naming

Use these names:

- `ResourceReference`: raw expression from configuration, CLI arguments, or API
  input. It may contain nested schemes such as `file:env:ORION_CONFIG`.
- `ResourceAddress`: resolved concrete address after reference dereferencing.
  This is what backend selection consumes.
- `ResourceScheme`: normalized scheme value. This can keep the current name or
  become `ResourceAddressScheme` if the rename is done broadly.

Examples:

```text
ResourceReference.parse("file:env:ORION_CONFIG")
ResourceAddress.parse("file:/etc/orion/orion.yml")
ResourceScheme.FILE
```

### Nested Scheme Semantics

Nested references allow the outer scheme to declare the expected backend while
the inner expression supplies the backend operand.

Example:

```text
file:env:ORION_CONFIG
```

Resolution:

1. `env:ORION_CONFIG` reads the environment variable value.
2. The result is used as the operand for `file:`.
3. The final address is a file address.

This lets operators keep configuration stable while moving the concrete path or
URI into the environment:

```yaml
security:
  keyStore:
    location: file:env:ORION_KEYSTORE_PATH
```

The config file says "this is a file-backed keystore"; the environment decides
which file.

### Supported Forms

Direct addresses:

- `/etc/orion/orion.yml`
- `file:/etc/orion/orion.yml`
- `s3://bucket/path/orion.yml`
- `git+ssh://git@example.test/orion/config.git?path=orion.yml`
- `local:orion/config`
- `classpath://defaults/orion.yml`

Reference providers:

- `env:NAME`
- `content:...`
- `content:base64,...`

Nested references:

- `file:env:ORION_CONFIG`
- `s3:env:ORION_CONFIG_S3_URI`
- `git+ssh:env:ORION_CONFIG_GIT_URI`
- `content:env:ORION_INLINE_CONFIG`
- `file:content:/tmp/orion.yml`

The exact normalization for nested values should be explicit. For example, if
`ORION_CONFIG=/etc/orion/orion.yml`, then `file:env:ORION_CONFIG` resolves to
`file:/etc/orion/orion.yml`. If `ORION_CONFIG=file:/etc/orion/orion.yml`, the
resolver should not produce `file:file:/...`; it should recognize that the inner
value is already a file address and keep it equivalent to the outer scheme.

### Resolution API

Introduce a resolver boundary:

```java
ResourceReference reference = ResourceReference.parse("file:env:ORION_CONFIG");
ResourceAddress address = resourceReferenceResolver.resolve(reference, context);
```

Context should include:

```text
phase
usage
allowed capabilities
max dereference depth
whether secrets may be revealed
```

Example:

```java
record ResourceReferenceResolutionScope(
        BootstrapPhase phase,
        ResourceUsage usage,
        Set<ResourceCapability> requiredCapabilities,
        int maxDepth) {
}
```

The resolved `ResourceAddress` then goes to `ResourceBackendRegistry`.

### Resolver Client Boundary

Resolvers may need small backend clients to turn an address into something the
caller can use. This should be part of the shared resource layer, not repeated
inside configuration, ACL, bootstrap, or secret code.

The initial client set should be deliberately minimal:

- HTTP/HTTPS: read bytes from a concrete URL with timeouts, size limits,
  redirect policy, and safe error reporting;
- S3-compatible storage: read an object by bucket/key and later expose a
  writable object-store adapter when the caller asks for write capability;
- Git: read a single path from a repository/ref without forcing feature code to
  clone or understand Git transport details, and expose a versioned writable
  store when the caller needs read-write semantics.

The resolver should hide these clients behind typed results. A caller that asks
for configuration content or a secret receives content bytes plus metadata. A
caller that asks for ACL storage receives a storage abstraction backed by local
directory, S3, Git, or another backend, and that abstraction can be read-write.

Feature code should not receive raw HTTP, S3, or Git clients unless the feature
explicitly is a transport feature. Most callers should depend on one of these
forms:

```text
read-only content: bytes/stream + source metadata
read-write content store: load/save/delete/list as needed
versioned content store: load/save with version or commit metadata
repository provider: backend-specific repository access
```

### Resolution Rules

- Resolve from the innermost reference provider outward.
- `env:` returns the value of an environment variable.
- `content:` returns inline content or bytes.
- `content:base64,...` returns decoded bytes.
- Outer schemes define the expected backend type.
- If an inner resolved value already has the same concrete scheme as the outer
  scheme, keep it as-is.
- If an inner resolved value has a conflicting concrete scheme, fail unless a
  backend explicitly supports that coercion.
- Apply phase and capability checks after dereferencing.
- Enforce a max dereference depth, initially 5.
- Detect direct cycles where possible.
- Never log secret dereferenced values.

Examples:

```text
file:env:ORION_CONFIG
  env -> /etc/orion/orion.yml
  result -> file:/etc/orion/orion.yml

s3:env:ORION_REPOSITORY
  env -> s3://bucket/repositories/team/project
  result -> s3://bucket/repositories/team/project

git+ssh:env:ORION_CONFIG_REPO
  env -> git+ssh://git@example.test/orion/config.git?path=orion.yml
  result -> git+ssh://git@example.test/orion/config.git?path=orion.yml

file:env:ORION_CONFIG
  env -> s3://bucket/orion.yml
  result -> error, because file cannot coerce an s3 address
```

### Bootstrap Safety

Nested references do not bypass bootstrap restrictions.

For application configuration resolution:

```text
phase = PRE_CONFIGURATION
```

If dereferencing produces `local:...`, resolution must fail because internal
Orion storage is not initialized yet.

Example:

```text
file:env:ORION_CONFIG
  env -> local:config/orion.yml
  result -> error
```

The error should name the original reference and the resolved scheme without
printing secret values:

```text
Resource reference file:env:ORION_CONFIG resolves to local: storage, which
cannot be used before Orion runtime storage is initialized
```

### Security and Logging

`ResourceReference` should preserve a safe display form.

Safe:

```text
file:env:ORION_CONFIG
s3:env:ORION_REPOSITORY
content:<redacted>
content:base64,<redacted>
```

Unsafe:

```text
expanded environment variable value
inline secret content
keystore password
private key content
```

The resolver should return both the resolved address and safe diagnostics:

```java
record ResolvedResourceAddress(
        ResourceReference original,
        ResourceAddress address,
        String safeDisplayName) {
}
```

### Migration Plan

1. Add `ResourceReference` and `ResourceAddress` alongside existing
   `ResourceLocation`.
2. Move parsing tests from `ResourceLocationTest` into the new names.
3. Add nested reference tests for `file:env:...`, `s3:env:...`, and conflicting
   schemes.
4. Introduce `ResourceReferenceResolver` with `env:` and `content:` providers.
5. Teach the unified resource storage resolver to accept `ResourceReference`
   and use `ResourceAddress` internally.
6. Replace feature code imports from `ResourceLocation` to `ResourceAddress`.
7. Keep a temporary adapter from `ResourceLocation` to `ResourceAddress` only
   while migration is in progress.
8. Remove `ResourceLocation` after all callers move.

### Test Matrix

Cover at least these cases:

- plain path becomes a file-like empty-scheme address;
- `file:/tmp/orion.yml` parses as file address;
- `file:env:ORION_CONFIG` resolves to a file address from the environment;
- `file:env:ORION_CONFIG` fails if the environment variable is missing;
- `file:env:ORION_CONFIG` fails if the environment value is an incompatible
  `s3:` address;
- `s3:env:ORION_REPOSITORY` resolves when the environment contains an S3 URI;
- `git+ssh:env:ORION_CONFIG_REPO` resolves when the environment contains a
  Git URI;
- nested references obey max depth;
- nested references cannot produce `local:` during `PRE_CONFIGURATION`;
- safe display strings never include expanded environment values or inline
  content.

### Relationship to Unified Storage Resolution

`ResourceReference` and `ResourceAddress` are the input model for the unified
storage resolver.

The storage resolver should not parse nested schemes itself. It should receive a
resolved `ResourceAddress` and then select a backend based on usage, phase, and
capabilities.


---

## Unified Resource Storage Resolution

### Goal

Unify the logic that determines storage implementations from resource locations.

Orion currently has three separate resolution layers:

1. application configuration resolution from application parameters;
2. Git repository storage resolution from `storage.location` in bootstrap
   configuration;
3. ACL bootstrap storage resolution from `bootstrap.accessControl.location`.

These layers all parse locations and select implementations, but they do it with
different switch statements and different helper APIs. The target design should
use one shared backend-selection concept while still preserving the different
runtime constraints of each use case.

### Core Idea

Do not force every use case into one concrete storage interface. A configuration
file, a versioned ACL store, and a Git repository provider do not have identical
APIs.

Instead, create one shared resolution layer that maps:

```text
location + usage + phase + required capabilities + options
```

to the correct backend implementation or to a clear error.

The result type may differ by use case:

- content store;
- versioned content store;
- Git repository provider;
- read-only bootstrap source.

But the rules for interpreting `file:`, empty paths, `s3:`, `git+...`,
`local:`, `classpath:`, `env:`, and `content:` should live in one registry.

### Content Access vs Storage Access

The shared resolver should distinguish "read this referenced thing" from "open
this as a storage backend".

For read-only inputs such as application configuration, bootstrap files, and
secrets, the resolved implementation can be a content source:

```text
ResourceContent / ContentSource
```

The resolver owns the small client needed to perform the read:

- local file reader for plain paths and `file:`;
- classpath reader for bundled defaults;
- HTTP/HTTPS client for URL content;
- S3-compatible object client for `s3:`;
- Git single-path reader for `git+file:`, `git+ssh:`, `git+http:`, and
  `git+https:`.

For mutable domains such as ACL storage, token storage, key material storage, or
future repository-backed state, the caller should request a storage capability
instead of raw content. The returned implementation must be a read-write store
when the requested capabilities include writing:

```text
READ_CONTENT -> content source
READ_CONTENT + WRITE_CONTENT -> writable content store
READ_CONTENT + WRITE_CONTENT + VERSIONED_CONTENT -> versioned content store
REPOSITORY_STORAGE -> repository provider
```

This keeps configuration and secret loading simple while letting ACL resolve to
a storage abstraction backed by a local directory, S3, Git, or another backend.
The feature layer should not need to know whether the resolver used HTTP, S3, or
Git clients internally to access the requested path.

### Request Model

Introduce a request object:

```java
record ResourceStorageRequest(
        String location,
        ResourceUsage usage,
        BootstrapPhase phase,
        Set<ResourceCapability> requiredCapabilities,
        Map<String, String> options) {
}
```

Example usages:

```text
usage = CONFIGURATION_FILE
phase = PRE_CONFIGURATION
required = READ_CONTENT

usage = GIT_REPOSITORY_STORAGE
phase = RUNTIME_BOOTSTRAP
required = REPOSITORY_STORAGE

usage = ACL_STORAGE
phase = RUNTIME_BOOTSTRAP
required = READ_CONTENT + WRITE_CONTENT
```

Example capabilities:

```text
READ_CONTENT
WRITE_CONTENT
VERSIONED_CONTENT
REPOSITORY_STORAGE
READ_ONLY
PRE_CONFIGURATION_SAFE
RUNTIME_ONLY
```

### Backend Registry

Add a `ResourceBackendRegistry` with pluggable backends:

```text
FileResourceBackend
ClasspathResourceBackend
S3ResourceBackend
RemoteGitResourceBackend
LocalOrionRepositoryBackend
EnvResourceBackend
ContentResourceBackend
```

Each backend declares:

- supported schemes;
- supported capabilities;
- allowed bootstrap phases;
- whether the backend is read-only or writable;
- how it maps query/auth/options to implementation-specific settings.

The registry should use `ResourceLocation` and `ResourceScheme` as the common
parser. Query parameter parsing, secret reference handling, S3 bucket/key
parsing, and remote Git URI normalization should move out of feature-specific
resolvers into shared helpers.

### Bootstrap Phase Rules

Configuration resolution happens before the application configuration exists.
Therefore it can only use backends marked `PRE_CONFIGURATION_SAFE`.

Allowed for application configuration:

- empty scheme plain path;
- `file:`;
- `classpath:`;
- `env:`;
- `content:`;
- `s3:`;
- `git+file:`;
- `git+ssh:`;
- `git+http:`;
- `git+https:`.

Rejected for application configuration:

- `local:`.

The important distinction is that `git+file:/some/repo.git?path=orion.yml` is
an external Git repository and can be used before Orion starts. `local:...` is
an internal Orion repository location and cannot be resolved before the runtime
storage is initialized.

The error should be explicit:

```text
local: locations cannot be used for application configuration because Orion
storage is not initialized yet
```

### Result Model

Use a sealed result hierarchy rather than returning `Object`:

```java
sealed interface ResolvedResourceStorage {
}

record ResolvedContentStore(ContentStore store) implements ResolvedResourceStorage {
}

record ResolvedVersionedContentStore(VersionedContentStore store)
        implements ResolvedResourceStorage {
}

record ResolvedRepositoryProvider(GitRepositoryProvider provider)
        implements ResolvedResourceStorage {
}
```

Concrete names can change during implementation, but the important boundary is
that feature code asks for capabilities and receives a typed backend, not a raw
scheme decision.

### Current Layer Mapping

#### Configuration

`LocationConfigurationProvider` should stop owning separate
`ConfigurationLocationReader` implementations. Instead it should request:

```text
CONFIGURATION_FILE + PRE_CONFIGURATION + READ_CONTENT
```

Then it parses the returned bytes as YAML or TOML based on the source name or
declared content metadata.

#### Git Repository Storage

`GitRepositoryProviderResolver` should stop switching directly on
`storage.location`. It should request:

```text
GIT_REPOSITORY_STORAGE + RUNTIME_BOOTSTRAP + REPOSITORY_STORAGE
```

Current mappings:

- empty path / `file:` -> `FileGitRepositoryProvider`;
- `s3:` -> `S3GitRepositoryProvider`.

Other schemes should fail with a capability error, not with an unrelated
scheme-specific message.

#### ACL Storage

`AccessControlStorageResolver` should stop owning the full switch over local,
remote Git, and S3. It should request:

```text
ACL_STORAGE + RUNTIME_BOOTSTRAP + READ_CONTENT + WRITE_CONTENT
```

Then adapt the resolved backend:

- plain path / `file:` -> non-versioned file content store;
- `s3:` -> non-versioned object content store;
- `git+file:`, `git+ssh:`, `git+http:`, `git+https:` -> versioned remote Git
  content store;
- `local:` -> versioned content store backed by the runtime
  `GitRepositoryProvider`.

The ACL layer may still need ACL-specific behavior around paths, branch names,
default file creation, and snapshot metadata, but it should not own backend
selection.

### Versioned vs Non-Versioned Storage

Versioning should be expressed as a capability rather than a separate resolver.

Examples:

- Configuration file load requires only `READ_CONTENT`.
- Runtime Git repository storage requires `REPOSITORY_STORAGE`.
- ACL storage can use non-versioned content stores, but should prefer or adapt
  versioned content stores when the backend supports `VERSIONED_CONTENT`.
- Future key material storage can request `READ_CONTENT + WRITE_CONTENT` and
  optionally use version checks for S3 ETags or Git commits.

If a caller requires versioning and the location resolves to a non-versioned
backend, the resolver should fail before feature code starts.

### Migration Steps

1. Extract common location helpers:
   - query parsing;
   - first-present query value lookup;
   - secret reference handling;
   - remote Git URI normalization;
   - S3 bucket/key parsing;
   - path normalization and traversal rejection.

2. Add `ResourceUsage`, `BootstrapPhase`, `ResourceCapability`, and
   `ResourceStorageRequest`.

3. Add `ResourceBackendRegistry` and initial backend declarations for existing
   schemes.

4. Add content-store interfaces:
   - read-only content source;
   - writable content store;
   - versioned content store.

5. Move file, classpath, S3, and remote Git single-file read/write code behind
   those stores.

6. Convert `LocationConfigurationProvider` to use the shared resolver in
   `PRE_CONFIGURATION` mode.

7. Convert `GitRepositoryProviderResolver` to use the shared resolver for
   `REPOSITORY_STORAGE`.

8. Convert `AccessControlStorageResolver` to use the shared resolver and only
   handle ACL-specific adaptation.

9. Remove duplicated scheme switches from the old resolver classes.

### Test Matrix

Cover at least these cases:

- configuration + plain path resolves to file content;
- configuration + `file:` resolves to file content;
- configuration + `classpath:` resolves read-only content;
- configuration + `s3:` resolves S3 content;
- configuration + `git+file:` resolves external Git file content;
- configuration + `local:` fails because runtime storage is unavailable;
- repository storage + empty path resolves file repository provider;
- repository storage + `file:` resolves file repository provider;
- repository storage + `s3:` resolves S3 repository provider;
- repository storage + `git+ssh:` fails because it does not provide
  `REPOSITORY_STORAGE`;
- ACL + `local:` resolves versioned internal repository content;
- ACL + `file:` resolves non-versioned local file content;
- ACL + `git+ssh:` resolves versioned remote Git content;
- a caller requiring `VERSIONED_CONTENT` fails against plain `file:` content.

### Expected Outcome

After this change Orion should have one way to determine which backend a
location names. Differences between configuration loading, Git repository
storage, ACL storage, and future key material storage should be represented by
usage, phase, and required capabilities, not by separate hand-written switch
statements.

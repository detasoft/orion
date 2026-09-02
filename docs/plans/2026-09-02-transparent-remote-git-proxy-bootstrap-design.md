# Transparent Remote Git Proxy Bootstrap

## Context

Orion previously loaded remote configuration and access-control repositories
through JGit. The JGit runtime was removed, but the product contract represented
by `git+ssh`, `git+http`, `git+https`, and `git+file` locations remains. Existing
integration tests expose the resulting gap: remote locations are unsupported,
some tests seed or inspect JGit-specific repository layout, and JGit Smart HTTP
pushes fail because Orion parses compressed POST bytes as pkt-line data.

Remote configuration is a bootstrap dependency. The repository may contain
both `orion.xml` and Orion's key store, so neither persistent proxy configuration
nor the material store is available when the first remote connection is made.
Every launch therefore supplies two external bootstrap secrets: a credential
for the remote Git server and the password used to open the key store fetched
from that repository.

## Goals

- Preserve direct startup from existing remote Git location schemes.
- Make a remote repository appear as a local native repository to configuration
  and ACL consumers.
- Start remote access before `orion.xml` and material initialization.
- Adopt the bootstrap proxy into persistent scoped configuration after
  bootstrap, without rewriting an existing entry on every restart.
- Store runtime credentials through encrypted configuration and the material
  store, never in a URI or plaintext configuration field.
- Show remote aliases distinctly in the admin API and UI while keeping their
  internal native repositories inaccessible through public Git routes.
- Retain interoperability tests without depending on JGit storage layout.

## Location Semantics

Location schemes select repository behavior:

- `local:<name>` resolves an ordinary local native repository and creates no
  proxy.
- `git+ssh://`, `git+http://`, `git+https://`, and `git+file://` identify remote
  repositories and automatically create transparent native proxies.

Persistent configuration assigns each repository an id within system,
organization, or project scope. A remote entry receives a generated internal
reference such as:

```text
git+proxy:system/remote-acl
git+proxy:org/acme/shared-settings
git+proxy:project/acme/backend/origin
```

The `git+proxy:` scheme is an Orion-internal reference. It is resolved by the
proxy registry and is not a network endpoint accepted by ordinary Git clients.
The remote source URI remains the configured `git+ssh/http/https/file` value.

## Bootstrap Lifecycle

1. Parse the launch location and external bootstrap secret references.
2. If the location is remote, create a provisional transparent proxy before
   loading `orion.xml` or opening the material store.
3. Authenticate to the upstream with the supplied remote credential and make
   the selected ref available through a local native repository facade.
4. Read `orion.xml`, the key store, and other required bootstrap files through
   that facade.
5. Open the fetched key store with the separately supplied key-store password.
6. Build and validate the runtime configuration and material capabilities.
7. Find a persisted proxy with the same normalized upstream identity. If one
   exists, adopt the provisional proxy under that scoped identity without
   changing `orion.xml`. If none exists, add the proxy and its runtime
   credential once through the normal configuration update path.
8. Transfer the adopted proxy into the runtime lifecycle and clear bootstrap
   secret buffers owned by the bootstrap process.

This sequence runs on every process start. Persisted proxy state cannot replace
the external bootstrap secrets because both `orion.xml` and the key store are
behind the connection they unlock.

The bootstrap proxy is not allowed to activate stale configuration after an
upstream failure. Missing upstream state, failed authentication, a missing key
store, or an invalid key-store password fails root startup before public HTTP
or SSH transports start.

## Identity and Reconciliation

Proxy identity has two forms:

- a provisional identity derived from a canonical remote URI and selected ref;
- a persistent identity derived from configuration scope and repository id.

Canonicalization must preserve repository-significant path and transport data
while excluding credentials and file-selection parameters. Reconciliation may
adopt exactly one persisted entry. Multiple entries for the same normalized
upstream, mismatched transport data, or a collision between local and proxy
identities is a configuration error.

An existing persisted credential is not silently replaced by the bootstrap
credential. Credential rotation is an explicit audited operation. The
bootstrap credential remains valid only for bootstrap and the current proxy
handoff.

## Credential Storage

Bootstrap accepts secrets only through external `env:` or `file:` references.
Plaintext credentials in location URIs, process arguments, logs, proxy metadata,
or exception messages are rejected.

After the key store is open:

- passwords and tokens are sealed as authenticated encrypted envelopes in the
  owning `orion.xml` scope;
- SSH private keys are imported into purpose-scoped material storage and XML
  retains only the material alias;
- credential resolution is limited to the proxy's system, organization, or
  project scope and its ancestors;
- envelope authenticated data binds schema version, scope, proxy id,
  credential id, and credential kind.

The key-store password remains an external bootstrap secret and is never stored
inside the key store it unlocks.

## Proxy Runtime

The proxy owns a persistent local native repository and uses Orion's native Git
client transports for upstream synchronization. Configuration and ACL code use
the same `loadFiles` and `saveFiles` behavior as other local native repositories.

Before reads, the proxy refreshes the selected upstream ref subject to bounded
timeouts and pack limits. Writes create local native objects and commits, then
push with an expected old object id. A write succeeds only after the upstream
accepts the compare-and-set ref update. Authentication failures, non-fast-forward
updates, unavailable upstreams, and malformed remote data remain typed failures.

Proxy repositories are excluded from ordinary repository enumeration and from
public Git HTTP and SSH routing. Only the internal proxy registry resolves
`git+proxy:` references.

## Admin API and UI

Repository administration exposes local repositories and remote aliases as
separate collections. Each remote alias includes:

- scope and persistent id;
- sanitized upstream URI and transport;
- internal `git+proxy:` reference;
- credential kind and reference metadata without secret values;
- last successful synchronization, current state, and safe failure detail.

The UI presents this as a dedicated Remote aliases section. It supports copying
the internal reference, replacing credentials, and retrying synchronization.
It never exposes plaintext credentials, encrypted envelopes, private material,
or secret-bearing query parameters.

## Smart HTTP Compression

JGit may gzip Smart HTTP POST bodies and send `Content-Encoding: gzip` when the
compressed body is smaller. Orion must decode supported content encodings before
constructing the buffered pkt-line input. Identity bodies remain unchanged.
Unsupported encodings, corrupt gzip streams, decompression-limit violations,
and multiple ambiguous encodings return bounded client errors rather than
reaching the Git parser as arbitrary bytes.

## Testing

Focused tests cover location classification, canonical proxy identity,
bootstrap ordering, secret cleanup, reconciliation, duplicate rejection,
credential encryption, scope checks, proxy enumeration, and gzip decoding.

Integration tests use a second local Orion runtime as the remote HTTP or SSH
server. They cover direct remote launch, first-run proxy persistence, restart
without duplicate configuration, configuration and ACL reads and writes,
upstream compare-and-set conflicts, wrong credentials, missing remote state,
and local locations that must not create proxies.

Tests inspect repository state through native APIs and observable Git behavior.
They do not assume JGit's `config` file, a `master` default branch, or a bare
repository directory named after the logical repository. Lifecycle assertions
use the current native Git, SSH, and HTTP components.

The Docker-backed MinIO tests are independent of this work. They remain S3
integration coverage and require an available Docker daemon when that suite is
run.

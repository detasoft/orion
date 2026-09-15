# Persist Proxy Bindings and Resolve Git Credentials

Status: todo
- Owner: codex, session 01a09f53-ace6-7771-bae0-ceb63ee2e54e, started 2026-09-14 15:22 Europe/Amsterdam.
Depends on: completed bootstrap runtime boundaries (`ac2f610f`, `79dd66b4`),
existing configuration cipher and repository-remote schema (`d17476ab`, `146b9e76`).

## Required result

A provisional bootstrap proxy adopts one persistent scoped identity after
configuration/material validation. Runtime proxy and mirror connections can
resolve a stored Git credential after restart without exposing its value.

## Design and preserved behavior

Use the current `OrionDocument` identities, configuration update path,
`ConfigurationSecretReference`, and configuration cipher. Define the minimum
shared Git credential storage/resolution boundary with
[02/05](../02_hierarchical-orion-configuration/05_secret-reference-credential-management.md).
An extension for bootstrap system scope must be explicit and validated; existing
organization/repository references keep their meaning. Do not build a second
secret framework or put credentials into `RepositoryRemote` URIs.

Persist secret envelopes as `ConfigurationSecret(id, envelope)` in `secrets`
collections on the existing system, organization, and repository nodes. The
containing node supplies the owner; secret IDs are canonical and unique within
that owner. Absent collections in existing XML mean no stored secrets. The
schema carries an opaque envelope; the credential owner must parse, authenticate,
and decrypt it through the existing configuration cipher before use. Preserve
these collections when updating ACL data. This is the shared Git storage boundary
for 02/05, not a separate proxy-only credential store.

`ConfigurationSecrets` in `core/common` is the shared credential owner over
the current configuration snapshot and `ConfigurationCipherCapability`.
Repository consumers resolve only their own repository or its organization;
system secrets use an explicit separate entry point. Authenticate the full
owner address and secret ID in the existing cipher context. Create and replace
produce unpublished document candidates and consume the supplied characters;
the configuration update owner must persist them with its revision check.
The resolver does not cache plaintext or publish configuration itself.

Bootstrap proxy metadata belongs to `SystemConfiguration.proxies`. Each
`GitProxyBinding` has a stable `RemoteAlias`, canonical upstream URI and selected
ref, credential kind, and an optional ID in the existing system secret collection.
HTTP basic auth also carries its username; SSH may retain an external local
known-hosts file URI as trust input. No credential value or bootstrap cache name
belongs in this record. The model rejects duplicate aliases, duplicate canonical
upstream/ref pairs, missing system secrets, and inconsistent transport/auth fields.
Bootstrap and persistent bindings share URI/ref canonicalization and credential
kinds. Organization/repository secret references keep their existing scope rules.

`OrionKeyMaterial` owns the cluster-scoped AES `configuration-v1` cipher. Its
first seal persists the key before returning an envelope; opening an envelope
never creates a missing key. Failed material persistence invalidates the owner,
so another attempt must reopen the durable store and observe any concurrent winner.
`ConfigurationSecrets.validate` authenticates all envelopes in a supplied
document without publishing it or retaining decrypted values.

`ProxyAwareNativeGitRepositoryProvider.adoptProvisional` builds an unpublished
candidate from its existing sources. Source IDs determine aliases only for new
upstream/ref pairs; existing operator-selected aliases and credentials prevail.
`BootstrapContext.adoptProxies` persists this candidate through the existing
`AccessControlStorage` revision check, preserving all secondary files. It confirms
the result by reloading, recognizes a concurrent winner or a lost save response,
and bounds conflict retries to three saves. A concurrent material-store revision
change still requires reopening the bootstrap owner before another adoption attempt.

`ProxyAwareNativeGitRepositoryProvider.activate` consumes the current configuration
supplier and the shared credential owner. It validates all envelopes, requires
all provisional upstream/ref identities to have been adopted, and refreshes the
complete candidate before switching bindings. It retains the private cache names
used by resolved sources, releases external bootstrap credential references after
activation, and forbids reentering provisional resolution on that provider.
Failed activation preserves the preceding binding set; removing an active binding
revokes retained handles without exposing its cache.

The existing HTTP/SSH transport path resolves persistent metadata and credentials
from one current snapshot for every connection. Secret rotation and changes to
credential kind/reference take effect on the next connection. Matching remains
bound to canonical upstream/ref, so reassigning an alias cannot redirect an
already-resolved source. Plaintext characters are cleared on both successful and
failed operations. `BootstrapContext.configurationCipher` exposes the existing
typed capability for the same runtime credential owner.
Calling adoption and activation from application startup and binding the current
configuration supplier in runtime composition remain pending.

Bootstrap credentials remain external on every launch. Match adoption by
canonical upstream URI and selected ref, reject duplicate/colliding identities,
and retain the already-resolved source handles. Add absent metadata/credentials
once through an optimistic configuration update; never overwrite existing
credentials during restart. Retrying a lost response must not duplicate entries.

Use authenticated encryption with context bound to the owning scope and stable
credential identity. Cross-scope references are allowed only for a verified
ancestor. GitHub HTTPS token support is the first mirror consumer. Preserve
existing bootstrap SSH behavior; any persistent SSH credential must have one
explicit owner shared with 02/05, rather than being treated as a server host key.

## Implementation plan

1. Specify and test XML round-trip, identity collisions, and credential scope
   validation using the existing configuration model and cipher capabilities.
2. Implement the narrow credential resolver usable by both proxy bindings and
   `git-sync`; provide explicit create/replace semantics and safe diagnostics.
3. Reconcile provisional bindings with configuration and activate the existing
   provider only after the complete candidate validates. Keep failure cleanup
   and private-cache isolation intact.
4. Bind the shared resolver into runtime composition; credentials resolve for
   later operations after reload/rotation, with owned buffers cleared.
5. Exercise restart, concurrent first adoption, failed save, lost response,
   rotation, and wrong-scope access before considering adoption complete.

## Acceptance

One upstream/ref has one persistent identity, restart does not rewrite an
existing entry, and failed adoption cannot expose a cache as a local repository.
Tokens are absent from XML plaintext, logs, errors, and read APIs; tampered or
wrong-scope envelopes fail safely. A GitHub token reference resolves through the
same credential owner for an ordinary mirrored repository. Local repository use
retains its existing behavior. Run focused schema/credential/bootstrap/proxy
checks and the full JVM suite.

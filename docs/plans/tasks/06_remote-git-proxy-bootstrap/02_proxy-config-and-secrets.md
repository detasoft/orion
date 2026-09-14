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

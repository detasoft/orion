# KMS contract simplification and version semantics

## Objective

Make the KMS contract small and explicit. Use one serializable identifier to
reference a key, certificate, or certificate chain. Separate material identity,
stored content, cryptographic operations, and HTTP presentation.

This document records the agreed requirements and the analysis as of 2026-10-29.
The contract migration is partially implemented. The normative version rules
are also recorded in [Key Material Backup and Restore](../key-material-backup-and-restore.md#entry-versions-and-the-kms-revision).

## Implementation progress

The source-revision replacement is implemented in the working tree. Store,
service, facade, and modification use the opaque source revision. A modification's
`version()` returns its captured base revision. Numeric KMS counters and their
per-entry metadata have been removed. Git sources retain the branch revision
when material content is absent; the store distinguishes this from a persisted
snapshot. Conditional writes, history validation, and resource-specific events
remain in place.

The content source exposes `load()` returning `Content(revision, bytes)` instead
of a separate `revision()` query. The returned bytes and revision describe the
same source state. A pinned `read(revision)` remains available for events.
Only the decoded store retains the revision of its successfully loaded state.

The shared `KeyMaterialId(alias, version)` lives in `common-runtime`, in the
`pro.deta.orion.keymaterial` package. Schema references and their Editor, ACME,
TLS, HTTP, and test consumers use it instead of `OrionMaterialReference`.
Schema now depends on `common-runtime`; KMS does not depend on schema.
Alias normalization and the XML/JSON reference shape are preserved.

Stored-entry identity and descriptor versions are unified. `inventory()` returns
IDs; `read(KeyMaterialId)` returns `KeyMaterialEntry` with validated purposes,
algorithm, and Java `KeyStore.Entry` content. Exact historical reads survive
reload; deletion blocks all versions of the deleted alias. Modification methods
return the appended ID. HTTP DTOs and formatting live in separate HTTP classes.

ACME persists the new certificate and then updates its configured references
through Editor. TLS activation follows accepted configuration changes. When KMS
and XML share a Git branch, activation edits the loaded Editor snapshot so an
unrelated KMS commit does not falsely conflict with unchanged XML. Publication
failure leaves the previous configuration and its pinned material usable.
Signing supports retained versions within the same alias; JWT `kid` uses
`alias:version` and rejects alias-only IDs. Secret envelopes select their exact
wrapping-key versions.

The discrepancies below describe the initial implementation before these
replacements.

The byte-storage boundary is being extracted into `common-runtime` as
`BlobStorage`, with nested `Content(revision, bytes)` and
`Modification(expectedRevision, bytes)`. Memory, read-only, and protected local
file implementations have no KMS dependencies. `GitBlobStorage` lives beside
`GitFile` and receives commit metadata from its caller. `BlobChanged` reports the last explicitly loaded revision and the new source
revision to per-storage subscribers. Notifications do not reload or accept content; `KeyMaterialChanged` continues to describe decoded material
changes. The decoded owner is named `KeyMaterialRepository`. Subscriptions currently work through Git; local and in-memory implementations
have explicit TODOs and return no-op subscriptions. Configuration can reuse the
byte-storage contract in a separate change.

## Agreed requirements

### Exactly two version concepts

1. **Entry version** is a positive, incrementing number within one alias.
   The identifier is `KeyMaterialId(alias, version)` and can be serialized in
   XML references. Creation starts at `1`; an update appends version `n + 1`.
   Updating a certificate chain also updates the entry version even if its
   private key is unchanged. Existing entry versions are not overwritten.
2. **KMS revision** identifies the complete committed state. For Git storage
   it is the Git commit ID obtained from the content source. It is the basis
   of a conditional modification. Do not maintain a separate numeric KMS
   counter or a separate material-generation number.

A modification captures the store revision on creation and uses it as the
expected revision for publication. KMS does not increment or predict that
revision. The storage returns the committed revision after a successful write.
Only an entry version is calculated as `n + 1`.

Publishing an updated entry necessarily changes the KMS revision. Changing
the KMS revision does not mean that unrelated entries changed: their IDs remain
the same. Read an explicit ID exactly; never silently resolve it to the newest
version of its alias.

Version ordering exists only within one alias. Comparing versions of unrelated
aliases is meaningless. Active and verification signing materials are selected
by configuration, not by a comparison of their version numbers.

### Identity, content, and operations

- A material ID contains alias and numeric entry version. Algorithms, purposes,
  certificates, and key bytes are not part of the ID.
- Introduce one shared ID concept instead of parallel KMS and XML reference
  models. `OrionMaterialReference` already has the intended alias/version shape.
- Use one explicit read operation by ID rather than separate KMS getters for
  private keys, public keys, trusted certificates, and certificate chains.
- Keep enumeration separate from reading a selected entry. Decide whether the
  list returns IDs or metadata when defining the concrete contract.
- Algorithms and purposes remain validated entry metadata. Removing descriptor
  arguments must not remove operation-specific purpose and algorithm checks.
- Keep `sign`, `verify`, `seal`, and `open` in KMS during this transition.
- Own content types are acceptable if Java-to-domain and domain-to-Java
  conversions are supported. The concrete content representation is not yet
  agreed; do not automatically duplicate `KeyStore.Entry` with a parallel
  three-variant hierarchy.

`KeyMaterialContentStore.Entry` is currently a stored record, not an identifier.
A shorter top-level name such as `KeyMaterialEntry` is a candidate for the
record. It must not be confused with `KeyMaterialId` or `KeyStore.Entry`.

### HTTP boundary

Move response construction out of KMS. DTO definitions and formatting must be
in **separate classes** beside the HTTP route. Formatting includes public-key
PEM, fingerprints, subjects, issuers, serial numbers, dates, and DNS names.
Preserve the public response shape and cache policy. Do not serialize raw KMS
entries containing private or symmetric keys into an HTTP response.

## Initial implementation and discrepancies

### Storage and revisions

- `KeyMaterialContentStore` holds both `long revision` and `String sourceRevision`.
  It reconstructs the numeric revision from per-entry metadata and increments
  it on publication. Only the content-source revision should identify KMS state.
- Its stored `Entry.version` already increments per alias. However, `find(alias)`
  and service reads select the latest entry rather than an explicit alias/version.
- `KeyMaterialDescriptor.version` is stored separately in descriptor attributes.
  Certificate installation preserves those attributes while appending a new
  `Entry.version`. These two entry-version interpretations must become one.
- The append metadata currently stores alias, entry version, numeric KMS version,
  and a deletion marker. The persisted OID and format have explicit stability
  requirements in the storage class comment. Format changes require a deliberate
  decision about existing PKCS12 stores.
- `reloadState` currently uses numeric KMS versions to establish extension of
  history. After removing that counter, validate immutable existing entries,
  unique IDs, and valid per-alias extensions. Do not order opaque Git commit IDs.

### Modifications

- `KeyMaterialModification` takes a numeric expected KMS revision and exposes
  `version()` as its predicted numeric successor. A Git commit ID cannot be
  predicted this way. Replace this calculation with the captured store revision;
  exposing the modification's base revision remains valid. The newly committed
  revision comes from storage after publication.
- Its `add` method already calculates the next per-alias entry version, including
  pending additions. Make the resulting ID available to callers so configuration
  can select the actual committed entry.
- Reads and updates inside a modification must use its base state and pending
  additions. Avoid silently updating a newer entry when a caller targeted an
  older ID. Check both the requested entry and the expected whole-store revision.
- Preserve isolated preparation, conditional publication, failure handling,
  clearing of sensitive buffers, and events emitted outside storage locks.

### Git source

- `NativeGitKeyMaterialContentSource` already reads and writes commit IDs.
  Propagate them through the store, service, facade, and modifications.
- Its `revision()` currently reports absence when the PKCS12 file is absent,
  including when the branch already exists. Distinguish missing content from
  the base Git revision so creation uses the correct existing branch state.
- The shared `GitFile.write` currently permits an advanced branch head when
  the selected file blob is unchanged. Do not change that shared behavior
  accidentally while removing the KMS numeric counter.

### XML, ACME, TLS, and signing

- XML references already carry alias/version, but runtime readers currently
  resolve latest content by alias. Update all readers to exact IDs together.
- ACME currently installs a new chain without changing the configured material
  reference. It must obtain the appended entry ID and publish that reference
  through `OrionConfigurationEditor` after KMS persistence succeeds.
- KMS publication and configuration publication are separate operations. If
  configuration publication conflicts, the old configuration must continue to
  read its old entry. The unreferenced new entry can remain stored; do not roll
  back or overwrite persisted material to simulate a cross-store transaction.
- `SigningMaterialSet` incorrectly compares versions across aliases and rejects
  different descriptors for the same alias. Use full IDs for identity and
  deduplication; active `(signing, 2)` and verification `(signing, 1)` must be
  representable. Configuration chooses the signing policy.
- JWT `kid` currently contains only the alias. If keys can change under one
  alias, verification must identify the full entry ID. Decide the external
  token-format transition before implementation.
- Configuration secret envelopes already contain key alias and version. They
  must resolve the exact wrapping-key ID, including retained older versions.

## Proposed implementation order

1. **Define the concrete contracts.** Present the shared ID, entry/content
   representation, revision representation including an absent initial source,
   and modification results. Settle the decisions below before implementing
   their consequences. Choose an existing ownership boundary for the shared ID
   without making KMS depend on XML mapping. No new module is assumed.
2. **Use the source revision for KMS modifications.** Remove the numeric KMS
   counter throughout storage and real consumers. Preserve source-event races,
   history integrity, missing-file creation, and conditional writes.
3. **Unify entry versions and switch to exact IDs.** Update persistence,
   modifications, configuration references, ACME activation, signing selection,
   token identity, and secret resolution as one coherent version-semantics
   replacement. Do not leave old alias-only reads as a second production path.
4. **Consolidate reading and separate HTTP presentation.** Replace specialized
   read APIs with the agreed entry model; move response metadata into separate
   HTTP DTO and formatter classes. Remove obsolete internal paths and update
   every real consumer, including tests and integration fixtures.

The third item is the largest change: storage version selection and consumer
reference updates must agree.

## Existing data

Existing PKCS12 stores and configuration may be recreated. Preserving or
converting old data is not required. Change the persisted format directly;
do not add compatibility readers, migration paths, or reinterpretation of old
pinned IDs.

## Resolved decisions

- **Content representation.** `KeyMaterialEntry` holds Java `KeyStore.Entry`
  directly and exposes Java key/certificate projections. No parallel hierarchy.
- **Deletion and historical IDs.** Deletion remains fail-closed for all versions
  of the alias; historical lookup cannot bypass its tombstone.
- **Empty source.** `Content(revision, bytes)` preserves the branch revision
  with absent bytes. Both are absent only for an absent source.
- **External token identity.** JWT `kid` is `alias:version`. Previously issued
  alias-only tokens may be rejected; no compatibility lookup is retained.

## Verification scenarios

- Updating alias A advances its entry version and the source revision; alias B
  retains its exact ID. Reopening the store preserves those identities.
- Versions A/1 and A/2 remain distinguishable; reading A/1 never returns A/2.
- Independent modification instances based on the same source revision cannot
  overwrite each other. Failed publication does not partially activate entries.
- Creating the PKCS12 file works on an existing Git branch and on a new source.
- Reload preserves existing immutable history, rejects conflicting or replaced
  IDs, and publishes changes only for affected aliases.
- Certificate renewal produces a new ID; old configuration keeps its old chain
  until Editor publishes the new reference. A configuration conflict preserves
  the old active state and retained data.
- Signing selection supports different versions of one alias and arbitrary
  versions of unrelated aliases without cross-alias ordering assumptions.
- Retained signing and wrapping-key IDs resolve correctly; deleted material
  remains subject to the agreed deletion policy.
- Java/domain conversion round-trips all supported entry kinds and metadata.
  HTTP formatting preserves the response contract without exposing secrets.
- Preserve meaningful coverage through the replacement APIs and run the
  affected module tests and the complete repository test suite.

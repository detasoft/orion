# Native Pack Publication Store Design

## Goal

Publish received packs for file-backed native repositories through an explicit
pack publication store while keeping loose-object reads available during the
transition to pack-backed object lookup.

## Selected Approach

Use `PackPublicationStore` as the repository-internal storage boundary. The
local implementation stages pack bytes, index bytes, and manifest metadata under
`tmp/pack-publication`, then publishes the final pack, index, and manifest under
`packs/`.

This replaces the earlier idea of a narrow `FilePackPublisher` that only wrote
`<checksum>.pack` plus a simple JSON sidecar. Pack publication is now modeled as
an object-database concern with a manifest and object directory, not as a raw
file helper.

## Scope

The first implementation should affect file-backed native repositories only.
In-memory repositories may continue to ingest packs into loose quarantine without
writing pack files.

Successful pack ingestion should publish:

- the original pack bytes;
- index bytes for object lookup;
- a JSON manifest containing pack id/checksum, index checksum, byte length,
  object count, object ids, external base ids, source, visibility, and
  self-contained status;
- the existing loose objects used by current upload-pack and repository tests.

Invalid or incomplete packs must not leave visible published pack/index files.

## Storage Shape

Use the existing native repository directory and add a `packs/` directory:

```text
<repository>/
  objects/
  refs/
  packs/
    <pack-checksum>.pack
    <pack-checksum>.idx
    <pack-checksum>.json
  tmp/
    pack-publication/
```

The manifest is the visibility boundary for later readers. Readers should list
published manifests through `PackPublicationStore.publishedPacks()` and open
content through `openPublishedPack(...)`, rather than walking backend paths
directly.

## Data Flow

`PackIngestor` already validates the pack checksum and materializes objects into
a quarantine `LooseObjectStore`. During successful ingestion, it builds a
`PackPublicationRequest` from accepted pack bytes, generated index bytes, object
ids, and external base ids, then asks the configured `PackPublicationStore` to
publish it.

`NativeGitRepository` composes `PackPublicationStore` and `PackObjectDirectory`
with the existing loose object and ref stores. File-backed repositories use
`LocalPackPublicationStore` and `LocalPackObjectDirectory`; in-memory
repositories can keep `PackPublicationStore.NONE`.

If ref updates fail after pack publication, the pack remains valid but currently
unreferenced, matching the broader object publication plan.

## Error Handling

Pack parse and checksum failures stay reported through `PackParseException`.
Publication I/O failures may be unchecked at this layer, consistent with current
file-backed repository storage code.

Do not publish a final manifest unless the final pack and index are already
present. If a write fails before publication completes, clean up the transaction
directory and leave no visible manifest for readers.

## Testing

Add focused tests under `core/git-native-storage`:

- successful file-backed pack ingestion creates one `.pack` and one `.json`;
- successful publication creates matching `.pack`, `.idx`, and `.json` files;
- the manifest records checksum/id, index checksum, byte length, object count,
  object ids, external base ids, source, visibility, and self-contained status;
- published packs can be listed and opened through the publication store/object
  directory APIs;
- invalid or truncated pack input creates no visible pack/index files;
- existing loose-object ingestion behavior remains intact.

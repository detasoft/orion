# Temporary Pack Publication Index Design

## Goal

Publish received packs for file-backed native repositories with a durable,
temporary internal index while keeping the existing loose-object read path.

## Selected Approach

Use an internal JSON manifest/index beside the preserved pack bytes. This is a
temporary reader contract, not Git `.idx` version 2. It records enough metadata
to prove that a validated incoming pack was durably published and to support the
next pack-backed object lookup task.

## Scope

The first implementation should affect file-backed native repositories only.
In-memory repositories may continue to ingest packs into loose quarantine without
writing pack files.

Successful pack ingestion should publish:

- the original pack bytes;
- a JSON index/manifest containing pack checksum, byte length, object count, and
  object ids;
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
    <pack-checksum>.json
```

The JSON file is the temporary visibility boundary for later readers. It should
be written only after the pack bytes are complete and validated.

## Data Flow

`PackIngestor` already validates the pack checksum and materializes objects into
a quarantine `LooseObjectStore`. Extend the ingestion result/session data to
carry accepted pack bytes and object ids without changing the existing
quarantine behavior.

`NativeGitRepository.publishObjectsAndRefs(...)` should publish pack files when
the repository has a file-backed pack publisher, then call the existing ref
update path that moves loose quarantine objects into the object store. If ref
updates fail after pack publication, the pack remains valid but currently
unreferenced, matching the broader object publication plan.

## Error Handling

Pack parse and checksum failures stay reported through `PackParseException`.
Publication I/O failures may be unchecked at this layer, consistent with current
file-backed repository storage code.

Do not publish JSON metadata unless the pack bytes are already present. If a
write fails before the JSON file is complete, later readers should ignore the
partial state.

## Testing

Add focused tests under `core/git-native-storage`:

- successful file-backed pack ingestion creates one `.pack` and one `.json`;
- the JSON records the checksum, byte length, object count, and ingested object
  id;
- invalid or truncated pack input creates no visible pack/index files;
- existing loose-object ingestion behavior remains intact.

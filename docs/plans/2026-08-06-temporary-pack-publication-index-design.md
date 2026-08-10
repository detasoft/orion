# Native Object-Location Publication Design

## Goal

Publish received packs for file-backed native repositories by making object
locations visible only after pack validation and object-location indexing
complete.

## Selected Approach

Use object-location records as the first publication catalog. Each indexed object
record should identify the object id and its physical location inside a durable
pack. Readers discover objects through committed object-location records, not by
scanning pack files.

Do not require a separate pack-level document in the first design. A pack-level
record can be added later for maintenance, lifecycle summaries, or diagnostics
if object-location records are not enough.

## Scope

The first implementation should affect file-backed native repositories only. In
memory repositories may continue to ingest packs into loose quarantine without
writing pack files.

Successful pack ingestion should publish:

- the original pack bytes;
- object-location records containing object id, pack id/checksum, offset, type,
  size, and any validation metadata needed by readers;
- the existing loose objects used by current upload-pack and repository tests.

Invalid or incomplete packs must not leave visible object-location records.

## Storage Shape

Use the existing native repository directory and add durable pack storage plus an
object-location catalog:

```text
<repository>/
  objects/
  refs/
  packs/
    <pack-checksum>.pack
  tmp/
    pack-publication/
```

Pack file existence is not a visibility signal. A pack becomes readable only for
objects that have committed object-location records. Packs without committed
object-location records are invisible and can be reindexed or cleaned up.

The object-location catalog may be Lucene-backed. If Lucene is used, its commit
is the publication boundary for object visibility. The pack file must be fully
written, flushed, and validated before object-location records are committed.

## Data Flow

`PackIngestor` validates the incoming pack and materializes objects into a
quarantine `LooseObjectStore` for the current transition path. In parallel, it
collects the object-location metadata needed to make pack-backed reads possible.

Publication order:

1. Write the pack bytes under a content-addressed pack id.
2. Flush and validate the pack checksum, object count, deltas, and object graph
   required by the caller.
3. Build object-location records for every object in the accepted pack.
4. Commit object-location records.
5. Update refs only after object-location records are visible to readers.

If ref updates fail after object-location publication, the pack remains valid but
currently unreferenced, matching the broader object publication plan.

## Error Handling

Pack parse and checksum failures stay reported through `PackParseException`.
Publication I/O failures may be unchecked at this layer, consistent with current
file-backed repository storage code.

Do not commit object-location records unless the pack bytes are durable and
validated. If a write fails before object-location commit, leave no visible
object records for readers. Recovery can either reindex durable unreferenced
packs or delete them.

If object-location records are visible but the referenced pack is missing or
corrupt, treat it as storage corruption. The catalog should be rebuildable from
durable pack files where practical.

## Testing

Add focused tests under `core/git-native-storage`:

- successful file-backed pack ingestion creates a durable pack file and visible
  object-location records;
- object-location records include enough data to open the object from the pack;
- readers find objects through the object-location catalog, not directory scans;
- invalid or truncated pack input creates no visible object-location records;
- ref updates happen only after object-location publication succeeds;
- existing loose-object ingestion behavior remains intact.

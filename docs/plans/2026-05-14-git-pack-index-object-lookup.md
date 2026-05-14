# Git Pack Index and Object Lookup

## Goal

Add Orion-owned pack index reading, pack index building, and object lookup over
stored Git pack files.

The pack parser can tell Orion what is inside a pack stream, and the pack storage
client can persist pack bytes. This plan adds the missing lookup layer: given an
object id, Orion should be able to find the pack and byte offset for that object
without importing the repository through JGit.

## Current State

The high-level Git pack parser and builder plan mentions pack indexes only as a
future input to byte-for-byte verification. The S3 pack storage client plan
requires range reads for future index access, but it does not define an index
format or lookup API.

The commit information model expects pack and object locations to exist so fast
reads can load underlying bytes without walking a JGit repository.

Current S3 repository code does not provide working object lookup. `S3ObjectReader`
returns no objects, `S3ObjectInserter` does not insert ordinary objects, and the
S3 repository provider reports repository operations as unsupported.

## Non-Goals

Do not replace receive-pack or upload-pack protocol negotiation in this phase.

Do not implement object reconstruction from deltas here. This layer locates pack
entries and exposes the compressed entry range or stream offset. Delta resolution
can consume these locations later.

Do not design a full Git multi-pack-index implementation first. Start with one
sidecar index per pack, then add a simple repository-level object directory over
multiple pack indexes.

Do not support SHA-256 repositories until the rest of Orion's Git object model is
hash-algorithm aware. Start with SHA-1 because the current code and JGit-backed
tests use SHA-1 object ids.

## Index Model

Introduce backend-independent value objects:

- `GitPackIndex`: pack hash, index hash, object count, entries;
- `GitPackIndexEntry`: object id, CRC32, pack offset;
- `GitPackLargeOffset`: 64-bit offset record for large packs;
- `GitPackObjectLocation`: repository, pack id, object id, offset, crc32;
- `GitPackIndexLookup`: exact object-id lookup plus optional abbreviated-id
  lookup;
- `GitPackObjectDirectory`: repository-level view over all visible pack indexes.

The first format target should be Git pack index version 2:

- 256-entry fanout table;
- sorted object ids;
- CRC32 table;
- 32-bit offset table;
- 64-bit large offset table when needed;
- pack checksum;
- index checksum.

Keep the index model separate from S3, filesystem, and JGit classes.

## Index Reader

Add an index reader that can parse `.idx` bytes or a bounded stream:

1. Validate magic and version.
2. Parse and validate the fanout table.
3. Read sorted object ids.
4. Read CRC32 values.
5. Read 32-bit offsets and referenced 64-bit offsets.
6. Read pack checksum and index checksum.
7. Reject duplicate object ids, unsorted object ids, invalid fanout counts, and
   malformed large-offset references.

Reader errors should include the index byte offset and the violated invariant.

## Index Builder

Add an index builder that consumes parsed pack entry metadata:

1. Compute the object id for each whole object.
2. Record pack offsets from the parser.
3. Compute or capture CRC32 over each compressed pack entry.
4. Sort entries by object id.
5. Build the fanout table.
6. Write version-2 index bytes.
7. Append the pack checksum and index checksum.

For delta entries, the builder can initially require a caller-provided resolved
object id. That keeps the index builder independent from delta resolution while
still allowing delta packs once the reconstruction layer exists.

## Object Lookup API

Add a small lookup boundary that can be backed by local files, S3 range reads, or
test byte arrays:

```text
findObject(objectId) -> Optional<GitPackObjectLocation>
openObjectEntry(location) -> bounded compressed entry stream
readObjectEntryHeader(location) -> object type, inflated size, delta base data
```

Exact object-id lookup should be the first milestone. Abbreviated lookup can be
added once multiple pack indexes are supported and ambiguity can be reported
cleanly.

The lookup layer should not inflate or fully materialize object contents unless a
caller asks for object reconstruction.

## Repository Object Directory

Add a repository-level object directory over visible pack indexes:

- list validated pack indexes from storage;
- load index metadata lazily;
- cache small fanout tables and index summaries;
- find an object by scanning candidate indexes;
- report ambiguity for abbreviated ids;
- ignore indexes whose pack checksum no longer matches the stored pack metadata;
- allow a full rebuild by scanning validated packs and regenerating indexes.

This directory should become the dependency for the commit information model and
later native upload-pack object enumeration.

## Storage Integration

Store generated indexes beside pack files. A practical key layout:

- `repositories/<repo>/packs/<pack-hash>.pack`
- `repositories/<repo>/packs/<pack-hash>.idx`
- `repositories/<repo>/packs/<pack-hash>.meta`

Pack and index publication should be atomic from the reader's perspective:

1. upload pack bytes under a temporary key;
2. parse and validate the pack;
3. build the index;
4. upload the index under a temporary key;
5. publish final metadata only after pack checksum and index checksum match.

Readers should only use packs with complete metadata and a matching index.

## Phased Plan

Phase 1: JGit index fixtures.

Generate deterministic pack plus index fixtures with JGit. Cover small packs,
multiple objects, boundary fanout buckets, and a synthetic large-offset fixture if
practical.

Phase 2: Pack index reader.

Parse version-2 `.idx` files and validate fanout, sorting, checksums, CRC table,
and offsets. Compare parsed entries with JGit fixture expectations.

Phase 3: Pack index builder.

Build `.idx` bytes from parsed pack entry metadata for no-delta packs. Verify the
generated index can locate every object and matches fixture expectations.

Phase 4: Single-pack object lookup.

Given a pack and index, find an object by id and open the bounded pack entry
range through an abstract byte-range reader.

Phase 5: Repository object directory.

Load multiple pack indexes for a repository, support exact id lookup across all
visible packs, then add abbreviated-id lookup with ambiguity handling.

Phase 6: Storage integration.

Persist `.idx` sidecars through local and S3-compatible storage adapters. Rebuild
missing indexes from pack bytes and reject mismatched pack/index pairs.

Phase 7: Delta-aware index building.

Once object reconstruction exists, support index building for packs containing
`OFS_DELTA` and `REF_DELTA` entries by resolving the final object id for each
delta entry.

## Open Questions

Should index bytes be stored exactly in Git `.idx` format only, or should Orion
also store a compact metadata summary for fast startup?

Should the repository-level object directory keep an in-memory cache of all
object ids, or load candidate index slices lazily through fanout ranges?

How should stale indexes be handled when a pack is garbage-collected or replaced?

Should pack index building live beside the raw pack parser, or in a separate
`git-pack-storage` layer that depends on parser metadata?

When abbreviated object ids are ambiguous across packs, should the API return all
matches or a typed ambiguity error?

## Verification

Cover at least these cases:

- parse a JGit-generated version-2 index for a one-object pack;
- parse indexes with objects distributed across multiple fanout buckets;
- reject invalid magic, unsupported version, decreasing fanout counts, unsorted
  object ids, duplicate ids, truncated tables, bad pack checksum, and bad index
  checksum;
- build an index for a no-delta pack and locate every object by exact id;
- generated fanout table matches sorted object ids;
- generated CRC32 values match the compressed pack entry bytes;
- generated index can be used by JGit for the same pack where practical;
- lookup returns no match for a missing object id;
- multi-pack lookup chooses the correct pack and offset;
- abbreviated lookup reports ambiguity instead of choosing arbitrarily;
- S3/local range-reader lookup opens only the requested pack entry range;
- missing or mismatched index triggers rebuild or a clear validation failure;
- later delta-aware indexing records the final object id for delta entries.

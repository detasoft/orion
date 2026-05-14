# Git Delta Resolution and Object Reconstruction

## Goal

Add a JGit-free layer that resolves Git pack delta entries and reconstructs full
inflated Git objects from parsed pack data.

The raw pack parser can expose whole-object entries, `OFS_DELTA` entries, and
`REF_DELTA` entries. The native object model can parse inflated commit, tree,
tag, and blob content. This plan connects those layers: it applies Git delta
instructions, resolves delta bases, computes final object ids, and produces
object records that storage, indexes, and remote single-file operations can use.

## Current State

The pack parsing plan intentionally stops at raw entry parsing and delta metadata.
It does not resolve deltas.

The native Git object model plan assumes callers can provide inflated object
content. It does not define how to reconstruct content from pack delta entries.

The pack index/object lookup plan needs final object ids for every pack entry.
Whole-object entries can compute ids directly, but delta entries require base
resolution and delta application first.

Current S3 exploratory pack code relies on JGit `PackParser` callbacks. There is
no Orion-owned delta resolver or reconstructed object stream.

## Non-Goals

Do not parse pkt-line, side-band, or Git protocol messages here.

Do not implement raw pack header parsing here. Consume the raw pack parser's
entry model.

Do not implement pack building or delta generation here. This layer applies
existing delta instructions from incoming packs.

Do not persist reconstructed objects directly. Return them through a storage
agnostic API so local, S3, and in-memory stores can decide how to persist.

Do not depend on JGit in production code. Tests may compare reconstructed output
against JGit or Git CLI fixtures.

Do not require the first implementation to handle arbitrarily large object graphs
fully in memory. Add limits and streaming boundaries from the beginning.

## Delta Formats

Support both Git pack delta entry types:

- `OFS_DELTA`: base object is another object in the same pack, referenced by
  negative offset from the delta entry;
- `REF_DELTA`: base object is referenced by object id and may be in the same pack
  or in existing object storage.

Support Git delta instruction payload:

- source size varint;
- result size varint;
- copy instructions;
- insert instructions;
- reserved opcode rejection;
- result-size validation.

The delta instruction parser should be reusable by tests and by future delta
generation validation.

## Reconstruction Model

Introduce reconstruction-facing value objects:

- `GitPackEntryRef`: pack id and stream offset;
- `GitPackWholeObject`: entry ref, object type, inflated size, content handle;
- `GitPackDeltaObject`: entry ref, delta kind, base offset or base object id,
  inflated delta size, delta content handle;
- `GitReconstructedObject`: entry ref, object id, object type, inflated size,
  content handle, base chain metadata;
- `GitDeltaBaseRef`: base by pack offset or object id;
- `GitDeltaResolutionError`: entry ref, base ref, phase, and cause.

For small fixtures, content handles may be byte arrays. Production APIs should
allow bounded streams or temporary-file backed content when configured limits are
exceeded.

## Base Lookup

Add a base lookup boundary:

```text
findByPackOffset(packId, offset) -> Optional<GitReconstructedObject>
findByObjectId(objectId) -> Optional<GitReconstructedObject>
loadExternalObject(objectId) -> Optional<GitObjectRecord>
```

Lookup sources:

- already reconstructed whole objects from the current pack;
- already reconstructed deltas from the current pack;
- whole-object entries later in the same pack when dependency ordering requires
  a graph pass;
- existing object storage for `REF_DELTA`;
- temporary in-memory map for remote single-file fetch packs.

The resolver should not assume pack entries are already in dependency order. It
should detect unresolved bases and process entries when their bases become
available.

## Resolution Algorithm

Use a deterministic graph-based resolver:

1. Register every pack entry by stream offset.
2. Reconstruct all whole-object entries and index them by offset and object id.
3. Register all delta entries with their base references.
4. Repeatedly resolve delta entries whose base object is available.
5. Apply delta instructions to base content.
6. Compute final object id from base object type and reconstructed content.
7. Index reconstructed delta output by offset and object id.
8. Stop when all entries are resolved or no progress is possible.
9. Report missing bases, cycles, excessive chain depth, or size limit failures.

Git deltas do not store the final object type; the final type is inherited from
the base object. The resolver must preserve that rule.

## Delta Application

Delta application should validate every invariant:

- source size in delta payload equals base content size;
- result size in delta payload equals reconstructed result size;
- copy source offset and length stay within base content;
- insert length is nonzero and within payload bounds;
- reserved opcode `0x00` is rejected;
- final output does not exceed configured maximum object size;
- chain depth does not exceed configured maximum.

Use straightforward loops and bounds checks. Avoid clever buffer slicing that
makes malformed-delta behavior hard to audit.

## Memory and Streaming Limits

Define explicit limits:

- maximum base object size;
- maximum reconstructed object size;
- maximum delta payload size;
- maximum delta chain depth;
- maximum unresolved delta count;
- maximum total reconstructed bytes retained in memory.

For the first implementation, small objects can be byte arrays. Add a clear
transition point for large objects:

- spill inflated base/result content to temporary files;
- stream copy/insert operations where possible;
- keep object id calculation streaming over reconstructed content.

Remote single-file fetch can use stricter limits than repository import.

## Error Handling

Use typed failures:

- missing offset base;
- missing ref base;
- delta cycle;
- chain depth exceeded;
- malformed delta varint;
- reserved delta opcode;
- copy outside base;
- truncated insert;
- source size mismatch;
- result size mismatch;
- object size limit exceeded;
- storage lookup failure.

Errors must include pack id, entry offset, delta kind, base reference, and phase.
Do not persist partial reconstructed objects after a fatal pack-level failure.

## Integration With Pack Index

The pack index builder needs final object ids and CRC32 values.

This resolver should return:

- final object id for every entry;
- final object type;
- stream offset;
- base chain metadata;
- whether the object was whole or delta;
- reconstructed size.

CRC32 over compressed entry bytes remains a pack parser/index concern. The
resolver should not recompute compressed-entry CRC unless the raw parser chooses
to expose it through entry metadata.

## Integration With Object Model

After reconstruction, feed each `GitReconstructedObject` into the native object
model parser:

- commit parser for commit content;
- tree parser for tree content;
- tag parser for tag content;
- blob handling for blob content.

This allows remote single-file fetch to resolve tree paths even when the fetched
pack uses deltas for commit, tree, or blob objects.

## Integration With Storage

Provide a storage-neutral sink:

```text
onReconstructedObject(GitReconstructedObject object)
```

Possible sinks:

- in-memory transient object store;
- pack index builder;
- commit information model projector;
- S3/local object location metadata writer;
- verification collector in tests.

Storage publication should happen only after the resolver has either completed
successfully or produced a failure policy that the caller accepts.

## Phased Plan

Phase 1: Delta instruction parser.

Parse source size, result size, copy instructions, insert instructions, and
reserved opcodes from byte-array fixtures. Apply deltas against caller-provided
base bytes.

Phase 2: Whole-object reconstruction flow.

Consume raw parser whole-object entries, compute object ids through the native
object model, and emit reconstructed objects without delta support.

Phase 3: Offset-delta resolution.

Resolve `OFS_DELTA` entries whose bases are in the same pack. Support dependency
chains, out-of-order entries, missing bases, and chain-depth limits.

Phase 4: Ref-delta resolution inside the pack.

Resolve `REF_DELTA` entries when the base object appears elsewhere in the same
pack and can be found by final object id.

Phase 5: External base lookup.

Resolve `REF_DELTA` entries against an external object store. Cover missing
external bases and storage lookup failures.

Phase 6: Resolver limits and spill policy.

Add configured size limits, unresolved-count limits, and a temporary-file spill
strategy or a documented first-phase byte-array limit.

Phase 7: Pack index integration.

Feed reconstructed final object ids into the pack index builder for packs
containing deltas.

Phase 8: Object model integration.

Parse reconstructed commit/tree/tag/blob content and feed remote single-file
fetch path resolution.

Phase 9: Repository import integration.

Use reconstructed objects to build commit information projections and storage
metadata after receive-pack or remote pack ingestion.

Phase 10: Performance and adversarial fixtures.

Add large delta chains, malformed payloads, repeated bases, and high fan-out
deltas to validate time, memory, and error behavior.

## Open Questions

Should reconstructed object content be stored in memory first, temporary files
first, or behind a pluggable content handle from day one?

Should the resolver stop on the first unresolved delta, or collect all unresolved
entries for better diagnostics?

Should external base lookup return canonical content only, or also object type
and cached parsed object metadata?

How should repository import handle thin packs whose bases are intentionally
outside the incoming pack?

Should delta chain depth limits differ between remote single-file fetch,
receive-pack ingestion, and offline repository maintenance?

Where should delta instruction parser live: with pack parsing, object
reconstruction, or a small shared Git binary-format package?

## Verification

Cover at least these cases:

- delta instruction parser applies insert-only, copy-only, and mixed
  copy/insert payloads;
- malformed delta varints, reserved opcode, truncated insert, and copy outside
  base are rejected;
- source size mismatch and result size mismatch are rejected;
- whole-object entries reconstruct to expected object ids;
- `OFS_DELTA` resolves against an earlier same-pack base;
- `OFS_DELTA` resolves when dependency processing requires multiple passes;
- missing offset base produces a typed failure with entry offset;
- `REF_DELTA` resolves against a same-pack base by final object id;
- `REF_DELTA` resolves against an external object store;
- missing external ref base produces a typed failure;
- delta chain depth limit is enforced;
- reconstructed commit/tree/blob/tag ids match Git CLI or JGit fixtures;
- pack index builder receives final object ids for delta entries;
- object model parsers can parse reconstructed commit and tree objects;
- remote single-file fetch can resolve a file from a delta-compressed pack;
- object size and total memory limits stop adversarial packs;
- production resolver has no JGit dependency.

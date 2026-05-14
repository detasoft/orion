# Git Pack Reuse and Multi-Pack Index Optimization

## Goal

Add a JGit-free optimization layer that can reuse existing pack data and locate
objects efficiently across many packs.

This plan covers:

- repository-level multi-pack object lookup after per-pack `.idx` support exists;
- MIDX-like summary data without requiring Git's on-disk multi-pack-index format
  in the first milestone;
- reverse-offset lookup for pack entries;
- safe reuse of existing whole-object pack entries;
- later reuse of existing delta entries and valid delta chains;
- range-read caching for local and S3-backed repositories;
- pack locality data for upload-pack, maintenance repack, and delta base
  selection.

The correctness baseline remains canonical object lookup plus no-delta pack
building. Pack reuse and multi-pack indexes are optimizations and must be safe to
disable.

## Current State

`docs/plans/2026-05-14-git-pack-index-object-lookup.md` defines Git pack index
reading/building and exact object lookup. It explicitly starts with one sidecar
index per pack and a small repository-level object directory. It says not to
design a full Git multi-pack-index first.

`docs/plans/2026-05-15-git-pack-delta-generation-thin-pack.md` defines outbound
delta generation and notes that existing pack reuse is a later optimization. It
identifies three reuse levels: canonical content reuse, compressed whole-object
payload reuse, and existing delta entry reuse.

`docs/plans/2026-05-15-native-git-s3-backend-integration.md` needs S3 range
reads, pack index lookup, metadata batching, and eventually reusing existing
deltas and pack slices for upload-pack.

`docs/plans/2026-05-15-git-reachability-acceleration-indexes.md` defines pack
locality summaries as a future optimization input for pack planning and delta
base selection.

`docs/plans/2026-05-14-git-repository-maintenance-gc.md` needs repack and
locality improvements after safe liveness and pruning exist.

`docs/plans/2026-05-14-native-git-upload-pack-serving.md` can initially build
new no-delta packs for selected objects, but open questions remain about serving
large repositories efficiently when existing packs already contain most required
objects.

## Non-Goals

Do not replace the basic pack index reader or builder. This plan depends on
validated per-pack indexes.

Do not implement raw pack parsing, delta reconstruction, or delta generation
here. This layer consumes those services.

Do not make Git's official `.midx` format mandatory in the first version. Orion
can start with its own backend-neutral summary and later add Git-compatible MIDX
import/export if useful.

Do not require pack reuse for correctness. Upload-pack, save-files, receive-pack,
and maintenance must continue to work when reuse is disabled.

Do not reuse delta entries unless every base relationship is proven valid for the
new output pack and receiver.

Do not use hidden refs, hidden objects, or unauthorized objects as proof for
reuse, thin-pack external bases, or pack locality decisions.

Do not optimize by shelling out to `git`.

Do not depend on JGit in production code. Tests may compare generated packs and
lookup behavior against Git CLI or JGit fixtures.

## Terminology

Pack directory:

- repository-level service that knows about all visible pack files, indexes, and
  metadata.

Multi-pack summary:

- Orion-owned index over many pack indexes;
- maps object id to one or more pack locations;
- may also store pack order, preferred location, object type, and size.

MIDX:

- Git's official multi-pack-index format;
- useful compatibility target later, but not required for the first Orion
  summary.

Reverse index:

- offset-to-object-id lookup for one pack;
- needed when scanning pack slices, validating delta chains, and reusing entries.

Pack slice:

- a contiguous byte range from an existing pack containing one or more complete
  pack entries.

Entry reuse:

- copying an existing pack entry's header and compressed payload into a new pack
  instead of inflating and recompressing the object.

Whole-object reuse:

- reusing an existing `COMMIT`, `TREE`, `BLOB`, or `TAG` entry.

Delta reuse:

- reusing an existing `REF_DELTA` or `OFS_DELTA` entry.

## Design Principles

Pack reuse is an optimization boundary. If any invariant is uncertain, the
planner should fall back to canonical object materialization and ordinary pack
writing.

Reuse decisions should be explicit. A pack writer should receive a plan that says
which entries are copied, which entries are rewritten, and which objects are
materialized.

The same query should be deterministic for the same repository state and policy.
Preferred pack selection, duplicate object handling, and tie-breaking must not
depend on map iteration order.

All summaries are derived data. They can be deleted and rebuilt from pack
manifests, pack indexes, and canonical pack bytes.

Access policy is applied before reuse. A fast path must not expose an object that
a canonical traversal would hide.

## Object Location Model

Extend object locations beyond a single pack index hit.

Candidate value objects:

- `GitPackLocation`;
- `GitPackEntryLocation`;
- `GitPackEntrySummary`;
- `GitPackPreferredLocation`;
- `GitMultiPackObjectDirectory`;
- `GitMultiPackSummary`;
- `GitPackReverseIndex`;
- `GitPackSlice`;
- `GitPackReusePlan`;
- `GitPackReuseDecision`;
- `GitPackReusePolicy`;
- `GitRangeReadCache`.

`GitPackEntryLocation` should contain:

- repository id;
- pack id;
- pack checksum;
- object id;
- object type when known;
- pack entry type;
- entry offset;
- entry header length;
- compressed payload offset;
- compressed payload length when known;
- inflated size when known;
- CRC32;
- delta base reference if any;
- validation state.

The first implementation can compute compressed payload length from the next
entry offset or pack trailer offset. Reverse indexes make that efficient.

## Multi-Pack Summary

The multi-pack summary should avoid scanning every pack index for common object
lookups.

Minimum data:

- summary id;
- schema version;
- repository id;
- object format;
- input pack manifest id;
- pack ids in deterministic order;
- object id;
- preferred pack ordinal;
- pack offset;
- duplicate count or duplicate marker;
- optional object type;
- optional size metadata;
- checksum.

Preferred pack selection:

1. prefer packs in the current visible manifest;
2. prefer non-orphaned packs;
3. prefer newest pack for duplicate object ids if publication order says newer
   wins;
4. prefer pack with valid index and checksum;
5. tie-break by pack id lexical order.

The summary should also expose all duplicate locations for validation,
maintenance, and diagnostic paths.

## Relationship To Git MIDX

Start with Orion-owned summary files.

Reasons:

- easier to keep backend-neutral for S3 and local stores;
- easier to include Orion manifest ids and validation state;
- avoids implementing every Git MIDX chunk before the native path is stable.

Later compatibility:

- read Git `.midx` files if importing repositories that already have them;
- write Git-compatible `.midx` if standard tooling benefits from it;
- write Git `.rev` reverse indexes if helpful for debugging or Git CLI
  interoperability.

Do not expose Git MIDX absence as a repository error. Orion's own summary is
enough for native code.

## Reverse Index

Per-pack reverse indexes map entry offsets back to object ids and entry
metadata.

Use cases:

- compute compressed entry length from current and next offsets;
- validate `OFS_DELTA` base offsets;
- build pack slices;
- support range reads for adjacent entries;
- diagnose corrupt indexes;
- feed pack locality summaries.

Initial representation:

- pack id;
- pack checksum;
- sorted offsets;
- object id per offset;
- entry type;
- next offset or trailer offset;
- checksum.

The reverse index can be built from:

- `.idx` entries plus raw pack header scan;
- pack parser entry metadata during pack publication;
- maintenance rebuild over pack bytes.

It should be rebuildable and optional. If missing, callers can scan the pack or
fall back to non-reuse behavior.

## Pack Entry Boundaries

Entry reuse requires exact boundaries.

For each entry:

- entry offset;
- header length;
- compressed payload offset;
- compressed payload length;
- next entry offset;
- trailer start offset.

The raw pack parser can expose entry offsets and header lengths. The index gives
object ids and CRC32. Reverse index connects offset and object id in both
directions.

Compressed payload length is:

```text
nextEntryOffset - compressedPayloadOffset
```

For the last entry:

```text
packTrailerOffset - compressedPayloadOffset
```

The pack trailer itself is never copied into a new pack. The new pack writer
computes a new trailer checksum over the new pack bytes.

## Whole-Object Entry Reuse

Whole-object reuse is the first safe reuse level.

Eligible entries:

- pack entry type is commit, tree, blob, or tag;
- object id matches requested object id;
- pack checksum and index checksum are valid;
- object is visible to the operation;
- compressed entry bytes are readable within limits;
- target object format matches repository format;
- reuse policy allows the compression settings difference.

Whole-object reuse writes:

- existing entry header bytes;
- existing compressed payload bytes;
- updates new pack checksum as bytes are copied.

It does not need to inflate the object. Tests should still verify copied entries
round-trip through native parser and Git tooling.

## REF_DELTA Reuse

`REF_DELTA` reuse can be safe when the base is available.

Eligible entries:

- target object id is known;
- final object type is known;
- base object id is known;
- base object is included in output pack or valid as a thin-pack external base;
- delta chain depth stays within policy;
- access policy permits target and base;
- filters and shallow rules permit the base relationship.

If the base is included in the output pack, entry bytes can usually be copied
unchanged because `REF_DELTA` references the base by object id.

If the base is external, thin-pack policy must prove the receiver has it. When
thin-pack is disabled, include the base or fall back to materialized whole-object
output.

## OFS_DELTA Reuse

`OFS_DELTA` reuse is harder because it encodes the base as a negative offset from
the delta entry.

Safe options:

1. Preserve a contiguous slice containing base and delta entries with the same
   relative offsets.
2. Rewrite the `OFS_DELTA` offset to match the new pack layout.
3. Convert `OFS_DELTA` to `REF_DELTA` when the base object id is known and the
   receiver supports it.
4. Materialize the target object and write a whole-object entry.

The first implementation should not copy standalone `OFS_DELTA` entries unless
it preserves the required base-relative layout exactly or rewrites the entry
through a tested encoder.

Fallback should be common and explicit.

## Delta Chain Validation

Delta reuse requires chain validation.

For each reused delta target:

1. resolve target final object id;
2. resolve final object type;
3. enumerate base chain;
4. ensure every base is included, external with valid thin proof, or materialized
   earlier;
5. enforce chain depth limit;
6. ensure no hidden object is used as a base;
7. ensure filter and shallow rules permit the base;
8. verify no cycle exists;
9. validate entry bytes or reconstruct sampled targets in strict mode.

If validation cannot complete within limits, fall back to whole-object output.

## Pack Slice Reuse

Pack slice reuse copies multiple adjacent entries from an existing pack.

Useful when:

- upload-pack selected many objects stored together;
- repack wants to preserve locality temporarily;
- S3 range reads can fetch one larger range instead of many small ranges.

Slice requirements:

- starts at an entry boundary;
- ends at an entry boundary or before pack trailer;
- contains only entries selected for output, or policy allows copying extra
  entries;
- every delta base relationship inside the slice remains valid;
- external bases outside the slice are included or allowed by thin-pack policy;
- access policy allows every copied object.

Copying extra objects is usually not acceptable for upload-pack because it can
leak objects or send unexpected data. Maintenance repack can copy extra reachable
objects if the repack plan includes them.

## Reuse Plan

The pack planner should produce a reuse plan before writing.

Plan entries:

- materialize canonical object;
- copy whole-object entry;
- copy `REF_DELTA` entry;
- copy preserved `OFS_DELTA` slice;
- rewrite delta entry;
- convert delta to whole object;
- skip object because already excluded by haves or filters.

Plan metadata:

- required capabilities;
- required external bases;
- pack ids and byte ranges;
- object ids included;
- object ids omitted;
- fallback reasons;
- validation diagnostics;
- estimated bytes saved;
- range read plan.

The writer should not invent reuse decisions while streaming. It may only apply
safe fallbacks allowed by the plan.

## Upload-Pack Integration

Upload-pack can use reuse after object selection is complete.

Flow:

1. Select objects through reachability and access policy.
2. Apply filters, shallow rules, and haves subtraction.
3. Ask multi-pack directory for preferred locations.
4. Build a reuse plan under negotiated capabilities.
5. Validate delta bases and thin-pack constraints.
6. Build a range read plan.
7. Stream copied entries and generated entries into a new response pack.
8. Compute a new pack trailer checksum.
9. Record reuse statistics in upload event.

No existing pack trailer is reused. The response pack is always a new pack
stream with its own header, entry count, entries, and trailer.

When reuse fails, upload-pack falls back to materializing selected objects and
writing an ordinary pack.

## Receive-Pack Integration

Receive-pack does not need pack reuse for incoming object publication.

It can benefit indirectly:

- publication stores reverse index metadata for future reuse;
- duplicate object detection uses multi-pack summary;
- post-receive projection rebuild uses fast object lookup;
- failed ref updates leave orphan packs that maintenance can later inspect.

Do not let receive-pack update refs based on a multi-pack summary alone. Ref
updates still require object graph validation against canonical pack/object
storage.

## Save-Files Integration

Native `saveFiles` can stay simple:

- build new objects;
- write a small no-delta or delta pack;
- publish pack and index;
- update ref.

Pack reuse is not needed for small internal config writes. However, save-files
publication should produce index and reverse-index metadata so future reads and
upload-pack can reuse those entries.

## Maintenance Repack Integration

Maintenance can use pack reuse differently from upload-pack.

Use cases:

- combine many small packs;
- drop duplicate object entries;
- compact unreachable/orphaned packs after retention;
- improve locality for popular refs;
- build fresh reachability summaries and multi-pack summaries.

Repack policy can allow:

- copying extra reachable objects;
- deeper delta validation;
- slower range scans;
- temporary high memory budgets;
- existing delta reuse after full chain validation.

After repack:

- publish new packs and indexes atomically;
- publish new multi-pack summary;
- mark old packs as superseded;
- keep rollback metadata until old packs are safe to prune.

## S3 Range Read Planning

S3 reuse must avoid many tiny requests.

Range read planner inputs:

- selected pack entry locations;
- reverse index entry boundaries;
- maximum range size;
- maximum gap to coalesce;
- maximum concurrent range reads;
- operation byte limit;
- cache policy.

Planner behavior:

- sort ranges by pack id and offset;
- coalesce nearby ranges when the gap is small and all copied bytes are allowed;
- avoid coalescing across hidden or unselected entries for upload-pack unless
  extra bytes are only discarded locally and never streamed;
- prefetch fanout/index summaries;
- keep pack bytes streaming where possible.

For upload-pack, coalescing may read extra bytes from S3 but must not write extra
objects to the client.

## Range Read Cache

Add a bounded cache for immutable pack data and small index data.

Safe cache keys:

- pack id plus pack checksum plus byte range;
- index id plus index checksum;
- reverse index id plus checksum;
- multi-pack summary id plus checksum;
- object id plus pack checksum for fully reconstructed immutable objects where
  allowed.

Unsafe cache keys:

- ref names without a ref snapshot id;
- current branch tips without versioning;
- access-policy-dependent visible object sets without subject and policy version;
- raw credentials or signed URLs.

Cache policies:

- small fanout table cache;
- index summary cache;
- reverse index cache;
- hot pack range cache;
- per-operation cache for parsed commit/tree metadata.

Caches must be bounded and clearable on repository manifest changes.

## Local Backend Considerations

Local files can use file channels or memory mapping later, but the first version
should keep the same range reader interface as S3.

Local optimizations:

- read adjacent ranges through one channel;
- avoid copying through large intermediate byte arrays;
- share index summary cache;
- verify file size and checksum before trusting cached offsets.

Do not make local-only APIs leak into pack planning. S3 and local should use the
same logical reuse plan.

## Consistency And Snapshots

Every reuse query must pin a repository storage snapshot.

Inputs:

- pack manifest id;
- ref snapshot id when object selection depends on refs;
- multi-pack summary id;
- access policy version;
- hash algorithm;
- optional reachability index generation id.

If any input changes while planning:

- retry on the new snapshot; or
- fail with a typed stale-snapshot result.

The writer should not mix pack locations from different pack manifests in one
reuse plan unless the storage service declares them compatible.

## Duplicate Objects

Repositories may contain duplicate object ids across packs.

Rules:

- exact lookup returns the preferred location plus duplicate metadata;
- validation and maintenance can request all locations;
- upload-pack uses preferred location unless range/locality policy chooses a
  different valid location;
- duplicate objects with mismatched reconstructed content indicate corruption;
- duplicate whole-object entries with same object id are safe to prefer by
  policy;
- duplicate delta entries require final object id validation before reuse.

Duplicate diagnostics should be deterministic.

## Access Policy And Visibility

Reuse must preserve the same visibility as canonical object selection.

Rules:

- never copy entries that were not selected for the operation;
- never copy extra entries to the client for upload-pack;
- do not use hidden refs to prove thin-pack external bases;
- do not expose object ids reachable only from hidden refs in user-visible
  diagnostics;
- apply partial clone filters before reuse planning;
- apply shallow boundaries before external base proof.

Internal maintenance can operate on all objects only under maintenance actor
policy and internal audit.

## API Shape

Candidate service APIs:

```text
GitMultiPackDirectory.open(repositorySnapshot) -> GitMultiPackReader
GitMultiPackReader.find(objectId) -> GitObjectLocationResult
GitMultiPackReader.findAll(objectId) -> List<GitPackEntryLocation>
GitReverseIndexStore.open(packId, checksum) -> GitPackReverseIndex
GitPackReusePlanner.plan(request, locations, policy) -> GitPackReusePlan
GitPackReuseWriter.write(plan, output) -> GitPackBuildResult
GitRangeReadPlanner.plan(locations, policy) -> GitRangeReadPlan
```

Transport and repository services should call higher-level pack planning APIs,
not parse multi-pack summary files directly.

## Failure Handling

Typed failures:

- multi-pack summary missing;
- multi-pack summary stale;
- unsupported summary schema;
- pack manifest mismatch;
- index checksum mismatch;
- pack checksum mismatch;
- reverse index missing;
- reverse index checksum mismatch;
- object location ambiguous;
- duplicate object corruption;
- entry boundary unknown;
- range read failed;
- reuse validation failed;
- delta base missing;
- delta chain too deep;
- hidden base rejected;
- filter/shallow conflict;
- cache entry stale;
- snapshot changed during planning.

Default runtime behavior:

- upload-pack falls back to materialization when safe;
- maintenance records blocker and skips destructive action;
- object lookup can fall back to scanning per-pack indexes;
- corruption failures should not be silently ignored.

## Observability

Metrics:

- multi-pack summary build duration;
- object lookup count;
- lookup hit via summary;
- lookup fallback to per-pack scan;
- duplicate object count;
- reverse index build duration;
- reuse plan object count;
- whole-object reuse count;
- ref-delta reuse count;
- ofs-delta reuse count;
- fallback count by reason;
- S3 range read count;
- S3 range bytes read;
- range coalescing savings;
- cache hit/miss by cache kind;
- upload-pack bytes copied from existing packs;
- upload-pack bytes recompressed.

Logs:

- summary publication;
- summary stale detection;
- corruption detection;
- reuse disabled reason;
- maintenance repack reuse decisions;
- range read failures.

Do not log raw pack bytes, object contents, credentials, or hidden ref names in
user-visible logs.

## Phased Plan

Phase 1: Pack entry boundary metadata.

- Extend pack publication metadata with entry header length, compressed payload
  offset, and next-entry offset where available.
- Add tests that boundaries match parser offsets.

Phase 2: Reverse index.

- Build reverse index from pack parser and `.idx` metadata.
- Support offset-to-object lookup and compressed length computation.
- Add checksum and stale-pack validation.

Phase 3: Multi-pack summary model.

- Define Orion-owned summary schema, manifest inputs, and preferred-location
  rules.
- Build summary from validated per-pack indexes.
- Add duplicate object reporting.

Phase 4: Multi-pack lookup reader.

- Implement exact object lookup through summary.
- Fall back to per-pack index scan when summary is missing or stale.
- Add deterministic duplicate handling.

Phase 5: Range read planner and cache.

- Add range coalescing for local and S3 range readers.
- Cache fanout tables, index summaries, reverse indexes, and hot ranges.
- Add cache invalidation on pack manifest changes.

Phase 6: Whole-object entry reuse.

- Copy existing whole-object entries into newly generated packs.
- Recompute new pack trailer checksum.
- Verify native parser, Git CLI, and JGit fixtures can read reused-entry packs.

Phase 7: Upload-pack reuse integration.

- Use selected object ids and multi-pack locations to build a reuse plan.
- Disable delta reuse initially.
- Fall back to ordinary pack building when validation fails.
- Record reuse statistics.

Phase 8: REF_DELTA reuse.

- Validate base availability.
- Copy `REF_DELTA` entries when bases are included or valid external bases.
- Enforce chain depth and access policy.

Phase 9: OFS_DELTA handling.

- Add safe fallback first.
- Then support offset rewrite or contiguous slice preservation.
- Convert to `REF_DELTA` when safe and supported.

Phase 10: Maintenance repack integration.

- Use multi-pack summary for duplicate detection.
- Build new packs with reuse where safe.
- Publish new summaries after repack.
- Keep rollback metadata for old packs.

Phase 11: S3 optimization.

- Batch index and reverse-index reads.
- Coalesce pack entry range reads.
- Add S3 parity tests with local backend.

Phase 12: Git MIDX compatibility.

- Evaluate reading Git `.midx`.
- Evaluate writing Git-compatible `.midx` and `.rev` files.
- Keep Orion summary as the primary native interface unless compatibility wins.

## Verification

Reverse index:

- offset-to-object lookup works for first, middle, and last pack entries;
- compressed payload length is correct for the last entry before trailer;
- invalid checksum rejects reverse index;
- stale pack checksum rejects reverse index.

Multi-pack summary:

- summary lookup finds objects across multiple packs;
- duplicate object ids report all locations;
- preferred location is deterministic;
- stale pack manifest forces rebuild or fallback;
- unsupported schema version fails clearly.

Range reads:

- local range reader returns exact entry bytes;
- S3 range reader returns exact entry bytes;
- adjacent entries can be coalesced;
- coalescing never writes unselected objects to upload-pack output;
- cache invalidates on manifest change.

Whole-object reuse:

- copied commit/tree/blob/tag entries parse correctly;
- new pack checksum is valid;
- generated pack index contains reused objects;
- reused pack can be read by native parser and Git CLI where practical.

Delta reuse:

- `REF_DELTA` reuse succeeds when base is included;
- `REF_DELTA` reuse fails or falls back when base is hidden;
- `OFS_DELTA` standalone copy is rejected until rewrite/slice support exists;
- delta chain depth limit is enforced;
- thin-pack external base proof is required for omitted bases.

Upload-pack:

- reuse-enabled output contains same object ids as no-reuse output;
- hidden refs are not leaked;
- filtered blobs are not accidentally copied;
- shallow boundaries do not use invalid external bases;
- fallback to materialization works when summary is stale.

Maintenance:

- duplicate object detection is deterministic;
- repack publishes new summary only after pack and indexes are durable;
- old packs remain readable until retention policy allows pruning;
- corruption prevents destructive cleanup.

Production boundary:

- production code has no JGit dependency;
- all reuse optimizations can be disabled by configuration.

## Rollout

Build reverse indexes and multi-pack summaries in read-only mode first.

Use summaries for exact object lookup only after per-pack lookup parity is stable.

Enable whole-object reuse for upload-pack behind a feature flag.

Keep delta reuse disabled until delta reconstruction and outbound delta planning
are stable.

Enable range read coalescing for S3 after local backend tests pass.

Use maintenance repack integration only after liveness, rollback, and pack
publication behavior are stable.

Defer Git-compatible MIDX writing until Orion's own summary has proven useful.

## Open Questions

Should Orion's multi-pack summary eventually become Git-compatible `.midx`, or
remain an internal manifest-index file?

Should preferred duplicate location favor newest pack, smallest range-read cost,
or maintenance-selected locality?

How large should the hot range cache be for local and S3 backends?

Should upload-pack ever copy extra reachable objects for locality, or always
send exactly selected objects?

Should `OFS_DELTA` reuse prefer offset rewrite, conversion to `REF_DELTA`, or
materialization?

Should maintenance repack and upload-pack share the same reuse planner with
different policies, or use separate planners?

How much reverse-index metadata should be stored at pack publication time versus
rebuilt lazily?

## Acceptance Criteria

Orion can locate objects across many packs through a deterministic repository
summary and fall back to per-pack indexes when the summary is unavailable.

Orion can compute pack entry byte ranges through reverse indexes and range
readers for local and S3 backends.

Upload-pack can reuse existing whole-object pack entries without JGit and still
produce a valid new pack stream with a correct checksum.

Delta reuse is guarded by explicit validation and falls back safely when bases,
filters, shallow rules, or visibility are uncertain.

Maintenance can use summaries to detect duplicates and prepare repack work
without relying on stale or partial derived data for destructive cleanup.

All pack reuse and multi-pack summary behavior can be disabled without breaking
repository correctness.

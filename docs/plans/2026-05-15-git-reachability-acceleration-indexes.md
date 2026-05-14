# Git Reachability Acceleration Indexes

## Goal

Add rebuildable acceleration indexes for native Git reachability and object
enumeration.

The first native reachability implementation should be correct by walking refs,
commits, trees, tags, and object storage directly. This plan defines the next
layer: commit generation indexes, bitmap-like object sets, ref reachability
snapshots, and storage manifests that make upload-pack, receive-pack, pack
building, and maintenance faster without making derived data the source of
truth.

The acceleration layer must be optional. If an index is missing, stale, corrupt,
or incomplete for a query, callers must either fall back to canonical traversal
or receive a typed "index unavailable" result according to their policy.

## Current State

`docs/plans/2026-05-14-git-reachability-object-enumeration.md` defines the
correct JGit-free graph walk and object closure API. It explicitly leaves
bitmaps, Bloom filters, generation-number indexes, and other performance
optimizations for later.

`docs/plans/2026-05-13-commit-information-model.md` defines a rebuildable
projection over commits, trees, refs, and file paths. It includes commit graph
relationships and reachability from refs, but it does not define a dedicated
query-optimized bitmap or generation index for native transport operations.

`docs/plans/2026-05-14-native-git-upload-pack-serving.md` needs fast
wants/haves subtraction and object selection for fetch responses. The first
version can walk the graph, but large repositories need compact reusable object
sets.

`docs/plans/2026-05-14-native-git-receive-pack-serving.md` needs fast-forward
validation and pushed object graph checks. Generation numbers can speed up
ancestor checks while preserving canonical fallback.

`docs/plans/2026-05-14-git-repository-maintenance-gc.md` needs liveness scans
and asks whether repacking should create reachability summaries or bitmaps. This
plan keeps that as a reusable index-building service that maintenance can call.

`docs/plans/2026-05-15-git-pack-delta-generation-thin-pack.md` needs proven
object availability for thin-pack external bases. Reachability indexes can speed
that proof, but they must not weaken visibility and access checks.

## Non-Goals

Do not replace canonical Git objects, refs, pack indexes, or object storage.

Do not make bitmap indexes required for correctness.

Do not implement the first reachability graph walk here. This plan builds on the
reachability service after it already produces correct results.

Do not require compatibility with Git's on-disk `.bitmap` format in the first
implementation. Orion can use its own backend-neutral representation first.

Do not implement path Bloom filters as part of the first bitmap milestone. Path
filters can be added after object reachability bitmaps are stable.

Do not expose hidden refs or protected objects through aggregate bitmaps. Index
lookups must be scoped by the same visibility policy as canonical traversal.

Do not use JGit in production code. Tests may compare results against Git CLI or
JGit-backed fixtures.

## Design Principles

Acceleration data is derived and disposable. It can be deleted and rebuilt from
canonical refs and object storage.

Each index publication must be atomic. Readers should see either the previous
complete index generation or the next complete index generation, never a partial
build.

Indexes must declare the canonical repository snapshot they describe:

- ref snapshot id;
- pack/object store manifest id;
- object format;
- index schema version;
- visibility scope when relevant.

If any declared input changes, readers must treat the index as stale unless the
query can safely use a subset index.

The query API should return diagnostics. Silent fallback hides performance and
correctness issues during rollout.

## Index Types

Use several small index types rather than one monolithic file.

Commit graph index:

- commit id;
- parent commit ids;
- root tree id;
- commit time;
- generation number;
- optional corrected commit date;
- reachability flags for selected refs or snapshots.

Object ordinal table:

- stable ordinal for each indexed object id within an index generation;
- object type;
- optional pack location;
- optional size metadata;
- reverse lookup from ordinal to object id.

Commit object bitmap:

- commit id;
- bitmap of all objects reachable from that commit;
- includes commit, root tree closure, nested trees, blobs, and annotated tags
  according to the closure policy.

Ref reachability snapshot:

- ref name;
- target object id;
- peeled object id when the ref is an annotated tag;
- bitmap or commit set reachable from that ref;
- visibility scope used to build the snapshot.

Pack locality summary:

- pack id;
- object ordinals present in the pack;
- byte size totals;
- object type counts;
- optional pack generation or publication time.

Index build manifest:

- index generation id;
- schema version;
- repository id;
- object format;
- input ref snapshot id;
- input pack manifest ids;
- created timestamp;
- builder version;
- list of segment files and checksums.

## Generation Numbers

Add a generation number per commit to speed ancestor checks and traversal
ordering.

Initial generation rule:

- root commits have generation 1;
- a commit's generation is one plus the maximum generation of its parents;
- missing parents make the index incomplete and should produce a typed build
  failure unless the repository policy permits partial indexes.

Usage:

- if `generation(base) > generation(tip)`, `base` cannot be an ancestor of
  `tip`;
- traversal can visit higher-generation commits first;
- merge-base and negotiation queries can prune impossible branches earlier.

Later optional corrected commit date:

- store a monotonic timestamp-like value compatible with Git commit-graph v2
  semantics;
- use it only for performance hints, not correctness;
- keep the simple generation number as the baseline.

## Object Ordinals

Bitmaps need compact ordinal positions.

The ordinal table should be deterministic for a given index generation. A
practical initial order:

1. commits by generation descending, then object id;
2. tags by object id;
3. trees by object id;
4. blobs by object id.

Pack-aware ordering can be introduced later for better compression and range
read locality.

The ordinal table must include:

- object id;
- object type;
- ordinal;
- optional source pack id;
- optional source pack offset;
- optional uncompressed size when known.

Readers must verify that a bitmap's declared ordinal table id matches the table
used to interpret it.

## Bitmap Representation

Start with an Orion-owned bitmap format.

Requirements:

- deterministic serialization;
- stable schema version;
- chunked reads for large repositories;
- support for local files and S3 object storage;
- checksum per segment;
- ability to OR, AND, AND_NOT, and count bits efficiently.

Implementation options:

- Java `BitSet` for the first in-memory implementation;
- a simple compressed run-length format for persisted segments;
- a Roaring-like format later if dependencies and licensing are acceptable;
- future Git `.bitmap` import/export as a compatibility feature.

The first persisted format can be simple:

```text
ORION-BITMAP v1
ordinal-table-id
bit-count
chunk-count
chunk offsets
chunk payloads
checksum
```

Avoid designing around Java object serialization.

## Commit Bitmaps

Commit bitmaps map each selected commit to all objects reachable from that
commit.

The builder should not create a full bitmap for every commit in very large
repositories in the first version. Use a selection policy:

- refs and tag tips;
- recent branch commits;
- merge bases that are frequently used in negotiation;
- periodic commits by generation interval;
- commits touched by recent receive-pack updates;
- maintenance-selected commits after repack.

Queries can combine bitmaps with canonical traversal:

1. Find the nearest indexed commit(s).
2. Use bitmap object sets for indexed commits.
3. Walk unindexed commits until an indexed ancestor is reached.
4. OR object sets together.
5. Subtract haves or excluded sets.

This hybrid path keeps memory bounded and avoids all-or-nothing indexing.

## Ref Reachability Snapshots

A ref reachability snapshot records what is reachable from a set of refs at a
specific ref snapshot id.

Use cases:

- upload-pack advertisement and wants access checks;
- hidden-ref filtering;
- GC liveness scans;
- branch deletion impact analysis;
- protected ref diagnostics.

The snapshot must be scoped:

- all refs;
- visible refs for a specific access policy;
- protected administrative refs;
- hidden/internal refs.

Do not answer a user-scoped visibility query from an all-refs bitmap unless the
query only needs a superset for safe internal liveness. For protocol responses,
visibility must be exact enough to avoid leaks.

## Upload-Pack Integration

Upload-pack can use acceleration indexes for:

- finding common commits from haves;
- subtracting objects reachable from haves;
- selecting objects reachable from wants;
- proving thin-pack external bases;
- estimating pack size before streaming;
- avoiding repeated tree walks for clone and fetch.

Query flow:

1. Resolve wants and haves through native refs/object lookup.
2. Apply access policy and hidden-ref filtering.
3. Ask the reachability index for object sets for visible wants and accepted
   haves.
4. If the index is complete for the query, compute `wantObjects - haveObjects`.
5. If partially complete, combine indexed sets with canonical traversal.
6. If unavailable and policy allows, fall back to canonical traversal.
7. Pass the selected object ids to the pack planner.

Thin-pack external base proof must use only visible accepted haves. A bitmap
from hidden refs cannot prove that the client has an omitted base.

## Receive-Pack Integration

Receive-pack can use generation indexes for fast-forward checks:

- reject impossible ancestry when generation proves old cannot be ancestor of
  new;
- walk parents from new to old with generation-aware pruning;
- fall back to canonical traversal when the index is stale or missing.

Pushed object graph validation still needs canonical object checks for newly
uploaded objects. The index can help only after objects are published into a
staged or temporary lookup view.

After a successful ref update:

- enqueue incremental index update for new commits and affected refs;
- mark affected ref snapshots stale;
- keep old indexes readable until replacement is complete;
- emit diagnostics if index update fails.

## GC And Maintenance Integration

Maintenance can use reachability indexes for liveness scans:

- load all-ref reachability snapshot;
- verify it matches the current ref snapshot and pack manifest;
- mark packs and objects with no reachable ordinals as candidates;
- fall back to canonical traversal before destructive pruning.

For repack:

- build or refresh commit bitmaps after pack publication;
- record pack locality summary for the new pack;
- optionally choose bitmap-selected commits near pack boundaries;
- publish index generation only after pack and index files are durable.

Destructive maintenance must not rely only on a stale or partial bitmap.

## Pack Builder And Delta Planner Integration

Pack building can use object sets from this index but should not depend on the
index format directly.

The reachability service should return ordinary object ids plus diagnostics.
Pack builders and delta planners should not read bitmap files.

Delta base selection can use index metadata:

- object type;
- object size;
- pack locality;
- known availability from haves;
- recent path/history hints from projections.

Thin-pack validation should consume a typed proof:

- base object id;
- proof source: accepted have, visible ref, or included pack base;
- ref snapshot id;
- visibility scope.

## Storage Layout

Use a backend-neutral logical layout.

Local repository example:

```text
orion/indexes/reachability/
  manifest.current
  generations/<generation-id>/manifest.json
  generations/<generation-id>/commit-graph.idx
  generations/<generation-id>/ordinals.idx
  generations/<generation-id>/commit-bitmaps/
  generations/<generation-id>/ref-snapshots/
  generations/<generation-id>/pack-locality/
```

S3 repository example:

```text
repositories/<repo-id>/indexes/reachability/current
repositories/<repo-id>/indexes/reachability/generations/<generation-id>/...
```

Publication:

1. Write all generation files under a staged generation id.
2. Validate checksums and internal references.
3. Write the generation manifest.
4. Atomically update the current pointer where supported.
5. For S3, publish by writing a new current manifest object with conditional
   update semantics or repository manifest integration.

Readers should pin a generation id for the duration of a query.

## Incremental Updates

After receive-pack or native save-files:

1. Capture old and new ref snapshot ids.
2. Identify new commits reachable from updated refs.
3. Parse new commits and trees from canonical object storage.
4. Add commit graph records for new commits.
5. Reuse existing bitmaps for unchanged ancestors.
6. Build bitmaps for selected new commits.
7. Rebuild affected ref reachability snapshots.
8. Publish a new index generation.

Branch deletion:

- does not delete commit graph records immediately;
- rebuilds ref reachability snapshots;
- leaves object liveness decisions to maintenance.

Force push:

- adds new reachable commits if needed;
- marks old ref reachability snapshot stale;
- does not remove old commit records until compaction.

Tag creation/deletion:

- updates peeled tag records and affected ref snapshots;
- annotated tag closure must include the tag object and target closure.

## Full Rebuild

Full rebuild is required when:

- schema version changes;
- index corruption is detected;
- pack manifests are repaired;
- a repository is imported;
- an administrator requests validation;
- incremental update fails repeatedly.

Rebuild flow:

1. Read canonical refs.
2. Read pack/object manifests.
3. Walk reachable commits, tags, trees, and blobs.
4. Build commit graph records and generation numbers.
5. Build ordinal table.
6. Build selected commit bitmaps.
7. Build ref reachability snapshots.
8. Verify sampled queries against canonical traversal.
9. Publish the new generation atomically.

If rebuild fails, the previous generation remains current.

## Consistency Checks

Each index generation should validate:

- manifest schema version is supported;
- object format matches repository object format;
- ref snapshot id matches declared refs;
- pack manifest ids match declared object storage state;
- bitmap ordinal table id matches;
- every bitmap bit is within ordinal range;
- every commit bitmap target exists and is a commit;
- parent references exist or are explicitly recorded as shallow/missing;
- checksums match segment payloads.

Runtime query checks:

- index generation is pinned;
- query visibility scope matches index visibility scope;
- stale generation behavior follows caller policy;
- fallback diagnostics are emitted.

## Staleness Policy

Expose explicit staleness policy per caller:

- `REQUIRE_CURRENT`: fail if index does not match current refs and object
  manifests;
- `ALLOW_STALE_FOR_ESTIMATE`: use stale data only for metrics or size estimates;
- `ALLOW_FALLBACK`: fall back to canonical traversal when stale;
- `ALLOW_SUBSET`: use index for known unchanged subset and traverse the rest.

Upload-pack should usually use `ALLOW_FALLBACK`.

Receive-pack fast-forward checks can use `ALLOW_FALLBACK`, but must not accept a
ref update based only on stale positive ancestry.

GC destructive pruning should use `REQUIRE_CURRENT` followed by canonical
validation for prune candidates.

## Visibility And Security

Indexes must not leak hidden refs or hidden objects.

Rules:

- ref snapshots are scoped by visibility policy;
- protocol queries use visible scoped indexes only;
- all-ref indexes are for internal maintenance, not direct user responses;
- diagnostics must avoid naming hidden refs in user-visible errors;
- thin-pack base proof uses accepted haves and visible refs only;
- protected refs and hidden refs are handled before object set selection.

If an index cannot answer a query without crossing visibility scopes, return
"index unavailable" and let the caller use canonical traversal with the correct
policy.

## API Shape

Add an acceleration API below `GitReachabilityService`.

Candidate interfaces:

- `GitReachabilityIndexStore`;
- `GitReachabilityIndexReader`;
- `GitReachabilityIndexBuilder`;
- `GitReachabilityIndexGeneration`;
- `GitObjectOrdinalTable`;
- `GitCommitGenerationIndex`;
- `GitObjectBitmapSet`;
- `GitRefReachabilitySnapshot`;
- `GitReachabilityIndexPolicy`;
- `GitReachabilityIndexDiagnostics`.

Example operations:

```text
openCurrent(repositoryId) -> GitReachabilityIndexReader
readCommitGeneration(commitId) -> GitCommitGenerationRecord
readCommitBitmap(commitId) -> GitObjectBitmapSet
readRefSnapshot(scope, refSnapshotId) -> GitRefReachabilitySnapshot
selectObjects(wants, haves, scope, policy) -> GitIndexedObjectSelectionResult
isAncestor(base, tip, policy) -> GitIndexedAncestryResult
```

The public reachability service should decide whether to call this API. Most
transport callers should continue using `GitReachabilityService`.

## Error Model

Use typed failures:

- index missing;
- index stale;
- unsupported schema version;
- checksum mismatch;
- ordinal table mismatch;
- visibility scope mismatch;
- incomplete commit graph;
- missing canonical object;
- pack manifest mismatch;
- build interrupted;
- build conflict with newer generation;
- query limit exceeded.

Errors should include:

- repository id;
- index generation id when known;
- index type;
- expected and actual snapshot ids;
- query type;
- caller policy;
- whether fallback was attempted.

Do not include hidden ref names in user-visible errors.

## Observability

Emit metrics:

- index build duration;
- index build input object count;
- indexed commit count;
- indexed object count;
- bitmap bytes by segment;
- ref snapshot count;
- query count by caller;
- query hit/miss/fallback count;
- stale index count;
- checksum failure count;
- canonical traversal avoided;
- canonical traversal fallback duration;
- S3 range read count and bytes for index reads.

Structured logs should record:

- generation publication;
- full rebuild start and completion;
- incremental update start and completion;
- fallback reason;
- corruption detection;
- stale index rejection.

## Phased Plan

Phase 1: Index generation manifest.

- Define schema version, input ref snapshot id, pack manifest ids, object format,
  and segment checksums.
- Implement local and in-memory stores.
- Add publication and reader pinning tests.

Phase 2: Commit generation index.

- Build parent lists and generation numbers from canonical commits.
- Add generation-aware `isAncestor` acceleration.
- Fall back to canonical traversal when the index is missing or stale.

Phase 3: Object ordinal table.

- Build deterministic ordinals for reachable objects.
- Store object type and optional pack location metadata.
- Verify lookup in both directions.

Phase 4: In-memory bitmap object sets.

- Add OR, AND, AND_NOT, contains, count, and iteration operations.
- Keep persistence simple or disabled until operations are tested.
- Verify object sets against canonical closure.

Phase 5: Commit bitmap builder.

- Build bitmaps for selected commits.
- Combine indexed ancestor bitmaps with canonical traversal for unindexed
  commits.
- Add selection policy for branch tips and recent commits.

Phase 6: Ref reachability snapshots.

- Build visible and all-ref snapshots.
- Scope snapshots by access policy.
- Integrate with upload-pack wants/haves subtraction in fallback mode.

Phase 7: Persisted bitmap segments.

- Add deterministic serialization.
- Add checksums and chunked reads.
- Implement local file persistence first, then S3 persistence.

Phase 8: Receive-pack acceleration.

- Use generation index for fast-forward pruning.
- Enqueue incremental index update after successful ref changes.
- Verify forced update and branch deletion behavior.

Phase 9: GC and maintenance integration.

- Use all-ref snapshots for liveness candidates.
- Keep canonical validation before destructive deletion.
- Build fresh indexes after repack publication.

Phase 10: S3 optimization.

- Batch index reads.
- Cache pinned generation manifests and hot bitmap segments.
- Add S3 failure and stale-current-pointer tests.

Phase 11: Pack locality summaries.

- Record pack membership and object type counts.
- Feed pack planner and delta base selection with locality hints.
- Keep pack encoding independent from index storage.

Phase 12: Optional path Bloom filters.

- Add path-change filters only after object reachability indexes are stable.
- Use them for path history and narrow file reads, not for object closure
  correctness.

## Verification

Commit generation index:

- root commit generation is 1;
- linear history generations increase by 1;
- merge commit generation is one plus maximum parent generation;
- impossible ancestry is rejected by generation comparison;
- stale index falls back or fails according to policy.

Ordinal table:

- same input produces same ordinal table;
- object id to ordinal lookup is exact;
- ordinal to object id lookup is exact;
- object type is preserved;
- unsupported object format is rejected.

Commit bitmaps:

- bitmap closure matches canonical traversal for a linear history;
- bitmap closure matches canonical traversal for a merge history;
- tree and blob closure is included;
- annotated tag closure includes tag object and target closure;
- unindexed commit combines canonical walk with indexed ancestor bitmap.

Ref snapshots:

- branch creation adds reachable objects to visible snapshot;
- branch deletion removes the ref from the snapshot but does not delete objects;
- tag creation and deletion update peeled targets;
- hidden refs do not appear in user-visible snapshots;
- all-ref maintenance snapshot includes hidden refs only for internal scope.

Upload-pack:

- wants/haves subtraction using bitmaps matches canonical traversal;
- thin-pack external base proof uses visible accepted haves only;
- missing or stale index falls back to canonical traversal;
- hidden-ref-only proof is rejected.

Receive-pack:

- fast-forward update succeeds with generation-aware traversal;
- non-fast-forward update is rejected unless force policy permits it;
- stale positive ancestry does not accept an unsafe update;
- successful update enqueues incremental index rebuild.

Maintenance:

- liveness scan from snapshot matches canonical traversal;
- stale snapshot is not used for destructive pruning;
- repack publication can trigger a fresh index generation;
- failed rebuild leaves previous generation current.

Persistence:

- manifest publication is atomic for readers;
- checksum mismatch rejects the generation;
- interrupted build is ignored;
- S3 current pointer update conflict preserves the winning generation;
- local and S3 stores return equivalent query results.

## Rollout

Keep all acceleration disabled by default until canonical reachability service
tests are stable.

Enable read-only commit generation checks first, with mandatory canonical
fallback.

Enable bitmap object selection for upload-pack behind a feature flag.

Enable incremental index updates after receive-pack only after full rebuild can
repair or replace broken generations.

Enable maintenance liveness acceleration only with canonical validation before
delete.

Do not make bitmap absence a production error until repository sizes require it
and fallback behavior has been observed.

## Open Questions

Should Orion's first persisted bitmap format be a simple custom chunked bitset,
or should the project adopt a Roaring-compatible representation?

Should commit bitmaps be built for every branch tip only, or also for periodic
commits by generation interval?

Should ref visibility snapshots be precomputed for common roles, or built on
demand from all-ref and hidden-ref components?

How should index generations be compacted after many incremental updates?

Should S3 current pointer publication be part of the repository manifest update,
or a separate conditional object?

When should pack locality order influence ordinal order?

Should path Bloom filters live in this reachability index package or in the
commit information projection package?

## Acceptance Criteria

Native reachability can use a commit generation index to speed ancestor checks
without changing correctness.

Native object selection can use bitmap-like object sets for wants/haves
subtraction and produce the same object ids as canonical traversal.

Index data is rebuildable, versioned, checksummed, and atomically published.

Readers can detect stale, corrupt, or visibility-mismatched indexes and fall
back or fail according to explicit policy.

Upload-pack, receive-pack, and maintenance can consume acceleration through the
reachability service without depending on the index storage format.

No destructive maintenance operation relies only on stale or partial bitmap
data.

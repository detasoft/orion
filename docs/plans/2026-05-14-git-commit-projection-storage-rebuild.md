# Git Commit Projection Storage and Rebuild

## Goal

Add a detailed storage, update, rebuild, and query plan for Orion's Git commit
information projection.

The projection should give native Git code fast answers for commit metadata,
parent relationships, ref snapshots, tag peeling, path lookups, directory
listings, and per-path history without walking packs through JGit on every
request.

The projection is not the source of truth. Canonical Git refs, packs, indexes,
and object bytes remain authoritative. The projection must be rebuildable,
versioned, and safe to discard when corruption or format mismatch is detected.

## Current State

The commit information model plan defines the high-level scope: commits, trees,
tags, refs, path indexes, snapshots, and rebuildability.

The native object model plan defines parsers and builders for commit, tree, tag,
and blob objects.

The object store and pack publication plan defines events and metadata that can
feed projection updates after packs are published.

The ref storage plan says ref update events should update projections.

The reachability plan can use commit parent lists, generation numbers, root tree
ids, and optional reverse reachability summaries as accelerators.

The maintenance plan needs projection rebuild, stale-state detection, and repair
actions.

What is missing is a concrete projection storage contract, record model, update
pipeline, full rebuild flow, atomic publication rule, and query API.

## Non-Goals

Do not make projection data canonical.

Do not implement pack parsing, object storage, ref storage, upload-pack,
receive-pack, or maintenance in this plan. This layer consumes those services.

Do not require the projection for correctness of file reads, reachability, or Git
protocol serving. Callers must have a canonical-object fallback or a typed
"projection unavailable" failure policy.

Do not store blob contents in the projection. Store blob ids, modes, sizes when
known, and object locations.

Do not implement distributed query engines or external databases first. Start
with backend-neutral records that can be persisted locally and in S3-compatible
storage.

Do not depend on JGit in production code. Tests may compare projection records
against Git CLI or JGit fixtures.

## Projection Boundary

Introduce small service boundaries:

- `GitCommitProjectionStore`;
- `GitCommitProjectionReader`;
- `GitCommitProjectionWriter`;
- `GitCommitProjectionUpdater`;
- `GitCommitProjectionRebuilder`;
- `GitCommitProjectionQueryService`;
- `GitProjectionSnapshot`;
- `GitProjectionTransaction`;
- `GitProjectionStatus`;
- `GitProjectionVersion`.

The store owns durable records and atomic active-version switching.

The updater consumes pack publication events and ref update events.

The rebuilder recreates a full projection from canonical refs and object
storage.

The query service exposes read-optimized operations to repository code without
leaking storage layout details.

## Record Families

Split projection data into immutable object facts and mutable repository state.

Immutable or append-friendly records:

- commit facts;
- tree facts;
- tree entry facts;
- tag facts;
- object location facts;
- path history records;
- commit generation metadata.

Mutable records:

- projection metadata;
- active projection pointer;
- ref snapshot records;
- ref-to-commit reachability summaries;
- rebuild status;
- compaction markers;
- hidden/broken projection markers.

Immutable records can be reused across ref changes. Mutable records should be
small and replaceable.

## Projection Metadata

Every projection version should have metadata:

- repository id;
- projection format version;
- hash algorithm;
- object store snapshot id or manifest watermark;
- ref snapshot id;
- created time;
- updated time;
- rebuild status;
- last successful rebuild time;
- last incremental update time;
- source operation ids consumed;
- schema feature flags;
- validation checksum or record counts;
- failure reason when stale or failed.

Statuses:

- `MISSING`;
- `BUILDING`;
- `ACTIVE`;
- `STALE`;
- `FAILED`;
- `HIDDEN`;
- `DELETING`.

Only `ACTIVE` projection versions should be used for normal fast-path reads.
`STALE` may be used only by callers that explicitly allow stale acceleration and
verify canonical data before returning results.

## Commit Records

Commit records should contain:

- commit id;
- root tree id;
- parent ids in commit order;
- author name/email/time/timezone raw fields;
- committer name/email/time/timezone raw fields;
- encoding;
- message summary or full message policy;
- extra headers;
- generation number when known;
- commit time for ordering;
- object location;
- parsed record checksum or source object checksum.

Generation number can start simple:

- root commits have generation `1`;
- a commit generation is `max(parent generation) + 1`;
- missing parent generation marks the record incomplete until parent data is
  loaded or canonical traversal fills it.

If generation calculation is unavailable during an incremental update, store the
commit facts first and fill generation metadata later.

## Tree Records

Tree records should contain:

- tree id;
- entry count;
- total encoded tree size;
- entries by name;
- optional child tree ids;
- optional blob count summary;
- object location;
- validation checksum.

Tree entry records should contain:

- parent tree id;
- entry name bytes or normalized display name;
- Git mode;
- object id;
- inferred object type from mode;
- sort position;
- blob size when known;
- executable/symlink/gitlink flags.

Tree records are immutable for a tree id and can be shared by many commits.

## Tag Records

Tag records should contain:

- tag object id;
- target object id;
- target type;
- tag name;
- tagger fields when present;
- message summary or full message policy;
- peeled target id when resolvable;
- peeled target type;
- peel status;
- object location.

Tag peeling should be rebuildable from canonical tag objects. A stale peeled
record must not override canonical parsing.

## Ref Snapshot Records

Ref projection should mirror visible ref state at a specific ref snapshot.

Records:

- ref name;
- target object id;
- symbolic target for `HEAD`;
- peeled tag target when known;
- visibility class: visible, hidden, internal;
- update time or event id;
- source ref store version/etag when available.

The projection should never expose hidden refs through a query path that is
intended for user-visible access checks.

Ref snapshots are important for deterministic query behavior. A reader should
know whether it is querying projection data for a specific ref snapshot or the
latest active snapshot.

## Path Index

The path index supports fast file reads, directory listings, and "what changed"
queries.

Suggested records:

- commit id;
- path;
- path segments or normalized key;
- object id at that path;
- mode at that path;
- entry type: blob, tree, symlink, gitlink, missing;
- parent path;
- tree id containing the entry;
- inherited-from parent commit when unchanged;
- change kind: add, modify, delete, mode change, rename unknown;
- blob size when known;
- first-seen commit id for unchanged reuse.

The first implementation does not need rename detection. It can represent
renames as delete plus add.

For large repositories, the projection should avoid materializing full per-commit
snapshots for every commit immediately. It can store tree facts plus commit path
deltas first, then materialize selected path indexes on demand.

## Query API

Expose focused queries:

- resolve ref to object id;
- resolve `HEAD`;
- get commit metadata;
- get parent ids;
- get root tree id;
- get generation number;
- list recent commits for a ref;
- answer `isAncestor` using projection data when allowed;
- resolve path at commit;
- list directory at commit;
- load path metadata without blob content;
- get path history for a path under a ref;
- get reachable visible refs for a commit or tag where available;
- get object location hints.

Projection queries should return typed states:

- hit;
- miss;
- stale;
- unavailable;
- inconsistent;
- limit exceeded.

Callers can then choose canonical fallback, fail closed, or schedule rebuild.

## Local Storage Layout

Use an explicit local layout under the native repository:

```text
<repo>/
  projections/
    active.json
    versions/
      <projection-id>/
        metadata.json
        commits/
        trees/
        tags/
        refs/
        paths/
        indexes/
    tmp/
      rebuild-<run-id>/
```

The exact on-disk format can start as line-delimited JSON or compact binary
records, but the service API should not expose that choice.

Publication rule:

1. write a new version under `tmp` or inactive `versions/<id>`;
2. validate record counts and checksums;
3. move or finalize the version directory;
4. atomically update `active.json`;
5. keep the old active projection until retention or compaction removes it.

Readers should open a projection snapshot by reading `active.json` first and then
using only that version id for the duration of the read.

## S3 Storage Layout

For S3-compatible storage:

```text
repositories/<repo>/projections/active.json
repositories/<repo>/projections/versions/<projection-id>/metadata.json
repositories/<repo>/projections/versions/<projection-id>/commits/...
repositories/<repo>/projections/versions/<projection-id>/trees/...
repositories/<repo>/projections/versions/<projection-id>/tags/...
repositories/<repo>/projections/versions/<projection-id>/refs/...
repositories/<repo>/projections/versions/<projection-id>/paths/...
repositories/<repo>/projections/tmp/<run-id>/...
```

The active pointer is the visibility boundary. A version is not active until the
pointer references it and metadata validates.

Because S3 has no atomic directory rename, rebuilds should write immutable
version keys and then update the active pointer with conditional writes where
available.

Readers must validate that the active pointer and metadata agree on projection
id and status.

## Incremental Update Inputs

The updater should consume:

- pack publication events;
- ref update events;
- receive-pack command results;
- internal `saveFiles` results;
- maintenance repair notifications;
- projection format upgrades.

Pack publication events can provide:

- pack id;
- object ids and types;
- commit parent/root-tree facts when already parsed;
- tag target facts;
- source operation id;
- visibility state.

Ref update events can provide:

- ref name;
- old target;
- new target;
- update type;
- expected-old result;
- event id;
- ref store version.

The updater should be idempotent. Replaying the same event should not duplicate
facts or move the projection backward.

## Incremental Update Flow

For a successful internal save or receive-pack:

1. receive pack publication event;
2. parse or load object facts for new commits, trees, tags, and blobs;
3. write immutable object fact records if absent;
4. compute generation metadata for commits whose parents are known;
5. write tree records and path deltas for new root trees;
6. receive ref update event;
7. update ref snapshot records;
8. update branch recent-commit indexes for affected refs;
9. mark projection active if the update fully succeeds;
10. mark projection stale and schedule rebuild if update partially fails.

The projection update should not roll back object publication or ref updates.
Canonical repository state remains valid even if projection update fails.

## Full Rebuild Flow

Full rebuild should create a new projection version from canonical state.

Flow:

1. capture object directory snapshot;
2. capture ref snapshot;
3. traverse visible refs and configured internal refs;
4. parse reachable commits, trees, and tags through the native object model;
5. write commit, tree, tag, and object location records;
6. compute generation numbers;
7. build ref snapshot records;
8. build selected path indexes and directory indexes;
9. validate projection records against canonical object ids;
10. publish the new projection version;
11. switch active pointer atomically;
12. retire old projection versions after retention.

If refs or object manifests change during rebuild, the rebuilder can either:

- publish the projection as stale and immediately schedule another update;
- discard and restart;
- publish only if the captured snapshots are still current.

The default should be conservative: do not mark a rebuilt projection active if
the source snapshots changed in a way the rebuilder did not include.

## Path Delta Building

Build path indexes by comparing a commit root tree with parent root trees.

Initial policy:

- root commit records every reachable path as added;
- single-parent commit records path changes against its first parent;
- merge commits can record direct root tree differences against first parent and
  keep full merge path history as a later optimization;
- rename detection is out of scope;
- unchanged subtrees should be reused by tree id.

Tree-id reuse is the key optimization. If a subtree object id is unchanged from a
parent commit, the projection can reuse existing directory/path records instead
of walking every nested entry again.

## Consistency and Validation

Projection records must be checked against canonical data.

Validation examples:

- commit record id equals hash of canonical commit content;
- commit root tree exists;
- parent ids are valid object ids;
- tree record id equals hash of canonical tree content;
- tree entries are sorted and match canonical tree bytes;
- tag target and peeled target match canonical tag parsing;
- ref snapshot target matches ref store snapshot used by rebuild;
- object location still resolves through object directory snapshot;
- path index object ids match tree traversal for sampled or full validation.

On mismatch, mark the projection failed or stale and prefer canonical reads.

## Concurrency

Projection reads should be lock-light.

Readers:

- open a projection snapshot from the active pointer;
- use one projection version for the whole query;
- tolerate active pointer changes during the query;
- fail typed errors if required records are missing.

Writers:

- write immutable facts idempotently;
- publish mutable indexes transactionally;
- avoid blocking repository ref updates;
- coordinate active pointer updates with compare-and-set where available.

Only one full rebuild should run per repository at a time. Incremental updates
may queue behind rebuild or mark the rebuilding version stale depending on
policy.

## Staleness Policy

Projection staleness should be explicit.

Mark projection stale when:

- a pack publication event was not processed;
- a ref update event was not processed;
- object storage repair hid or changed visible manifests;
- maintenance found projection/canonical mismatch;
- projection format version changed;
- rebuild source snapshots changed after capture;
- incremental update exceeded limits.

Callers should declare policy:

- require current projection;
- allow stale projection with canonical verification;
- use projection only as a hint;
- bypass projection.

Access-control-sensitive paths should avoid stale-only decisions unless they are
verified against canonical refs/objects.

## Compaction

Projection storage can grow over time.

Compaction tasks:

- remove old inactive projection versions after retention;
- compact append-only path history records;
- rebuild branch recent-commit indexes;
- remove facts for objects no longer present after garbage collection;
- rewrite metadata to the current format version.

Compaction should run through maintenance and should not make the active
projection unavailable unless the new compacted version is ready.

## Integration With Reachability

Reachability service can use the projection for:

- parent id lookup;
- generation-aware ancestor checks;
- root tree lookup;
- reachable ref summaries;
- recently computed liveness results.

The reachability service must still handle:

- projection miss;
- stale projection;
- projection/canonical mismatch;
- traversal limits.

If projection data is used for a security decision such as fetch access, the
caller should ensure hidden refs and current ref visibility are respected.

## Integration With Repository Reads

Native `loadFiles` can use projection as an acceleration:

1. resolve ref through current ref store or projection policy;
2. resolve commit and path metadata through projection;
3. load blob content through canonical object storage;
4. verify blob id and mode where required;
5. fall back to canonical tree walk if projection misses.

Projection should never return blob bytes. It returns ids and metadata that help
the repository load the canonical blob.

## Integration With Maintenance

Maintenance should be able to:

- report projection status;
- mark projection stale;
- rebuild projection;
- hide failed projection versions;
- remove inactive versions after retention;
- compare projection records with canonical objects;
- emit diagnostics for corrupt records.

Maintenance should not repair canonical Git objects by editing projection data.

## Metrics and Diagnostics

Record:

- projection update count;
- update duration;
- rebuild duration;
- records written by type;
- query hit/miss/stale counts;
- canonical fallback count;
- stale reasons;
- validation mismatch count;
- active pointer switch count;
- inactive versions retained;
- storage bytes used.

Errors should include repository id, projection id, phase, source event id, and
record type. Do not log blob contents or hidden ref names in user-visible
messages.

## Implementation Phases

Phase 1: Projection record model.

Define metadata, commit, tree, tag, ref snapshot, path index, object location,
status, and typed error models.

Phase 2: Local projection store.

Implement local inactive-version writes, active pointer reads, active pointer
switching, and snapshot readers.

Phase 3: Commit/tree/tag fact writer.

Consume native object model parsers and write immutable object fact records with
canonical id validation.

Phase 4: Query service.

Add commit metadata, parent lookup, root tree lookup, ref snapshot lookup, path
metadata lookup, and directory listing queries.

Phase 5: Incremental update from pack publication.

Process pack publication events, write new object facts, and mark projection
stale on partial failure.

Phase 6: Incremental update from ref events.

Update ref snapshot records and branch indexes after successful atomic ref
updates.

Phase 7: Full rebuild.

Build a new projection version from object directory and ref snapshots, validate
it, and switch active pointer only after success.

Phase 8: Path history and reuse.

Add path delta records, unchanged subtree reuse, per-path history, and large-tree
limits.

Phase 9: Reachability acceleration.

Use parent lists and generation numbers for ancestor checks and fetch/ref update
queries, with canonical fallback.

Phase 10: S3 projection store.

Map the same versioned projection contract onto S3 keys and conditional active
pointer writes.

Phase 11: Maintenance integration.

Add stale detection, rebuild tasks, inactive-version cleanup, compaction, and
diagnostics.

## Verification

Cover at least these cases:

- local store publishes a new active projection version atomically;
- failed rebuild leaves the old active projection in place;
- commit records match canonical commit object ids and parent order;
- tree records reject entries that do not match canonical tree bytes;
- tag records include peeled annotated tag targets;
- ref snapshot records preserve symbolic `HEAD`;
- hidden refs are not returned by visible-ref queries;
- path lookup resolves a nested file without walking unrelated trees;
- directory listing returns modes and object ids for one tree;
- path history records add, modify, delete, and mode-change events;
- unchanged subtree reuse avoids rebuilding nested path records;
- incremental pack update is idempotent when replayed;
- ref update event moves only the affected ref snapshot;
- projection stale marker is set when an incremental update fails;
- full rebuild from canonical storage produces equivalent query results;
- source snapshot change during rebuild prevents active publication or marks
  projection stale according to policy;
- reachability query using projection matches canonical traversal;
- stale projection triggers canonical fallback when policy allows it;
- projection/canonical mismatch is reported and does not corrupt canonical
  storage;
- S3 active pointer publication is the visibility boundary;
- inactive projection versions are retained until maintenance cleanup;
- production projection code has no JGit dependency.

## Open Questions

Should path index records be fully materialized for every commit, or should the
first implementation store tree facts plus selected materialized path caches?

Should commit messages be stored fully in the projection, truncated, or loaded
from canonical objects on demand?

Should generation numbers be computed during incremental updates immediately, or
filled by a background projection compaction task?

Should active projection pointer updates require that ref snapshots are still
current, or is marking the projection stale after publication acceptable?

What record format should be used first: JSON lines for debuggability, a compact
binary format, or a small embedded key-value store behind the projection API?

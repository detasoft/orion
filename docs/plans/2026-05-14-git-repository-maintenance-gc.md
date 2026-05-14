# Git Repository Maintenance and Garbage Collection

## Goal

Add a JGit-free maintenance layer for native Git repositories.

This layer should repair interrupted storage operations, verify pack/index
manifests, rebuild derived indexes, classify unreachable objects, prune expired
orphaned data, and eventually compact duplicate objects through repacking.

The first implementation should be conservative. It should make repositories
observable and repairable before it deletes anything. Destructive cleanup should
require explicit policy, retention windows, and reachability checks based on a
stable ref snapshot.

## Current State

The native repository backend plan records `last maintenance time` as repository
metadata and notes that failed ref updates can leave unreachable objects.

The object store and pack publication plan defines staged pack writes, manifests,
orphaned packs, repair cases, and publication visibility. It explicitly does not
implement garbage collection or repacking.

The receive-pack plan allows successful object publication followed by failed ref
updates. In that case, refs must remain unchanged and stored objects may remain
unreachable for later garbage collection.

The reachability plan can expose reachable and unreachable object sets, but it
does not delete objects.

The pack index plan raises stale index handling when a pack is replaced or
garbage-collected, but does not define maintenance policy.

There is no native service that scans repositories for interrupted publications,
missing indexes, corrupt manifests, orphaned packs, stale projections, or
duplicate object storage.

## Non-Goals

Do not implement pack parsing, delta reconstruction, object lookup, ref storage,
or protocol handling in this plan.

Do not delete any object data in the first read-only maintenance milestone.

Do not change refs as part of garbage collection. Ref updates remain owned by ref
storage and receive/save operations.

Do not require repacking for correctness. Repacking is an optimization after safe
verification and pruning exist.

Do not make the commit information projection canonical. It can be rebuilt by
maintenance, but Git objects and refs remain the source of truth.

Do not depend on JGit in production code. Tests may compare maintenance results
against Git CLI or JGit-created fixtures.

## Service Boundary

Introduce repository maintenance services and value objects:

- `GitRepositoryMaintenanceService`;
- `GitMaintenanceTask`;
- `GitMaintenancePolicy`;
- `GitMaintenanceRun`;
- `GitMaintenanceReport`;
- `GitRepositoryRepairPlan`;
- `GitRepositoryRepairAction`;
- `GitObjectLivenessScan`;
- `GitObjectLivenessResult`;
- `GitGarbageCollectionPlan`;
- `GitPruneCandidate`;
- `GitRepackPlan`;
- `GitMaintenanceLock`;
- `GitMaintenanceMetrics`.

The service should support:

- dry-run scans;
- repair-only runs;
- index/projection rebuild runs;
- garbage collection planning;
- approved pruning;
- optional repacking;
- per-repository and server-wide scheduling.

Every task should return a structured report with actions taken, actions skipped,
warnings, errors, and whether a later run should retry.

## Maintenance Policy

Configuration should be explicit.

Suggested policy fields:

- enable read-only verification;
- enable automatic repair of incomplete staged transactions;
- enable index rebuilds;
- enable projection rebuilds;
- enable orphan pruning;
- enable repacking;
- minimum orphan retention age;
- minimum deleted-manifest tombstone retention age;
- maximum maintenance runtime;
- maximum packs scanned per run;
- maximum bytes read per run;
- maximum objects visited per liveness scan;
- S3 cleanup batch size;
- whether destructive actions require an explicit administrative command.

Default policy should be safe:

- verification enabled;
- non-destructive repair enabled only for obviously incomplete staged data;
- destructive pruning disabled;
- repacking disabled;
- orphan retention longer than normal request retry windows.

## Maintenance Locks

Use a repository-level maintenance lock to avoid conflicting maintenance runs.

The lock should not block normal readers. It may coordinate with writers at
specific phases:

- repair staged transactions without blocking readers;
- verify manifests while writers continue publishing new manifests;
- create liveness ref snapshot without blocking readers;
- pause or coordinate destructive pruning with pack publication;
- avoid deleting data referenced by a manifest published after the snapshot.

For local storage, the lock can be file-based with stale lock detection.

For S3 storage, the lock can use conditional metadata writes or a small lock
record with owner id, started time, and expiry.

Lock expiry must be conservative. A second maintenance runner should prefer
skipping work over stealing a possibly active lock too aggressively.

## Read-Only Verification

The first maintenance mode should inspect and report without modifying storage.

Checks:

- repository metadata exists and matches the expected format version;
- `HEAD` and refs are readable;
- every published manifest references an existing pack and index;
- pack and index checksums match manifest metadata;
- index pack checksum matches pack trailer checksum;
- object count in manifest matches index;
- duplicate object ids are reported;
- staged transaction directories or prefixes are listed;
- tombstones and deleted markers are listed;
- projection status is current, stale, rebuilding, or failed;
- ref targets exist in published object storage.

Verification should create a report that can be used by repair and garbage
collection phases, but it should not mutate repository data.

## Repair Actions

Repair should be explicit and idempotent.

Safe repair actions:

- abort staged transactions older than a configured threshold with no active
  writer marker;
- remove temporary local transaction directories that were never published;
- abort incomplete S3 multipart uploads when the backend exposes them;
- rebuild a missing index from a valid pack;
- rewrite missing or corrupt summary metadata when pack and index are valid;
- hide a manifest that references missing or checksum-mismatched data;
- mark projection stale when projection records contradict canonical objects;
- schedule projection rebuild.

Unsafe or ambiguous repair actions should require administrative approval:

- deleting a final pack with no valid manifest;
- deleting an index with no pack;
- choosing between two manifests that claim the same pack id with different
  checksums;
- repairing refs that point to missing objects;
- removing data when S3 final publication status is uncertain.

Every repair action should be repeatable. Running the same repair twice should
not corrupt the repository or produce conflicting state.

## Liveness Model

Garbage collection needs a conservative object liveness model.

Inputs:

- stable ref snapshot from `GitRefStore`;
- visible published pack manifests;
- object directory snapshot;
- reachability service;
- optional commit information projection;
- optional object publication source metadata;
- retention policy.

Object states:

- `REACHABLE`: reachable from a visible ref;
- `RECENT_UNREACHABLE`: not reachable but younger than retention;
- `EXPIRED_UNREACHABLE`: not reachable and old enough for pruning;
- `BROKEN`: manifest/index/object data is inconsistent;
- `UNKNOWN`: traversal limits or storage errors prevented classification.

Only `EXPIRED_UNREACHABLE` objects from otherwise valid packs can become prune
candidates. `UNKNOWN` must never be pruned automatically.

## Ref Snapshot Safety

Use a two-snapshot strategy before destructive pruning.

Flow:

1. capture ref snapshot `A`;
2. compute reachable objects from `A`;
3. identify expired unreachable candidates;
4. before deletion, capture ref snapshot `B`;
5. if `A` and `B` differ in any relevant ref, abort or recompute;
6. verify candidate manifests still exist and still match checksums;
7. delete or tombstone only candidates that are still expired and unreachable.

This prevents maintenance from deleting objects that became reachable during a
concurrent push or save operation.

For S3, final deletion may lag behind tombstone publication. Readers should stop
using tombstoned manifests before backend objects are removed.

## Orphan Pack Retention

Failed ref updates and rejected pushes can leave valid orphaned packs.

Retention policy should consider:

- pack publication time;
- source operation;
- whether any ref ever pointed at an object in the pack;
- whether the pack is referenced by projection or maintenance state;
- whether a retry or client reconnect might still need diagnostics;
- repository-specific policy.

Default behavior:

- keep orphaned packs visible as valid object storage until retention expires;
- exclude orphaned packs from ref advertisement;
- include orphaned objects in object lookup only when explicitly requested by
  maintenance or validation, not normal upload-pack;
- mark expired orphaned packs as prune candidates after a liveness scan.

Normal readers should not see staged or rejected packs. Orphaned published packs
are valid storage but should not become externally visible unless refs make them
reachable.

## Pruning

Pruning should be manifest-driven.

Candidate deletion flow:

1. create a dry-run `GitGarbageCollectionPlan`;
2. classify candidate objects and packs;
3. choose whole-pack deletion where every object in the pack is prunable;
4. choose repack-before-delete when a pack mixes reachable and prunable objects;
5. publish tombstones or `DELETING` state;
6. refresh object directory snapshots used by readers;
7. remove pack/index bytes after tombstone visibility delay;
8. mark metadata `DELETED` or remove after tombstone retention.

The first destructive implementation should delete only whole packs that contain
no reachable objects. Mixed packs should wait for repacking support.

If any storage operation fails during pruning, the manifest should remain in a
state that lets repair retry or roll back safely.

## Repacking

Repacking is optional and should follow safe pruning.

Use cases:

- compact many small packs from internal saves;
- remove duplicate objects;
- split reachable objects away from expired unreachable objects;
- improve upload-pack locality;
- prepare future bitmap-like reachability summaries.

Repack flow:

1. select source packs and reachable object set;
2. build a new pack from selected live objects;
3. build and validate the new index;
4. publish the new pack through the object store publication service;
5. verify every selected live object resolves through the new pack;
6. tombstone old packs only after the new pack is visible;
7. keep old packs until retention expires or a second validation pass succeeds.

Repacking must preserve object ids and repository behavior. It should not rewrite
refs, commits, trees, or tags.

## Index Rebuilds

Maintenance should rebuild missing or stale indexes when pack bytes are valid.

Index rebuild flow:

1. read pack bytes through the storage adapter;
2. parse the pack with native pack parser;
3. reconstruct deltas if needed;
4. compute final object ids;
5. build a version-2 pack index;
6. verify index checksums and pack checksum;
7. publish the rebuilt index beside the pack;
8. update manifest metadata if needed.

If pack parsing fails, maintenance should mark the manifest broken and avoid
exposing it to normal object lookup.

## Projection Rebuilds

Maintenance should coordinate with the commit information model.

Projection tasks:

- detect stale, missing, failed, or version-mismatched projections;
- rebuild projections from canonical refs and object storage;
- compare rebuilt records with canonical object ids;
- publish rebuilt projection atomically;
- leave old projection active until rebuild succeeds;
- mark projection stale when object/ref data changed during rebuild.

Projection rebuild failure should not make the repository unreadable through
canonical object storage.

## Local Backend Cleanup

Local maintenance should handle:

- stale transaction directories;
- final pack/index/manifest mismatch;
- deleted or tombstoned manifest files;
- filesystem rename leftovers;
- lock files older than policy allows;
- temporary spill files from delta reconstruction or pack building.

Local deletion should prefer rename-to-trash or tombstone-first semantics where
possible. Direct deletion can come after the manifest is no longer visible to
readers.

## S3 Backend Cleanup

S3 maintenance should handle:

- incomplete multipart uploads;
- staged transaction prefixes;
- final keys without final manifests;
- tombstoned pack/index keys;
- metadata/checksum mismatch;
- eventual listing delays;
- retryable delete failures.

Because S3 listings and deletes can be delayed or partially fail, maintenance
should be idempotent and batch-oriented. It should record enough state to retry
without assuming that a missing key always means the previous delete succeeded.

## Scheduling

Maintenance can run in several modes:

- startup scan for interrupted local transactions;
- periodic read-only verification;
- periodic non-destructive repair;
- administrator-triggered dry-run garbage collection;
- administrator-approved pruning;
- low-priority repacking.

Per-repository scheduling should avoid running expensive work on every request.
Request paths may trigger lightweight checks only when metadata clearly indicates
repair is needed.

## Observability

Emit structured metrics:

- maintenance run count and duration;
- repositories scanned;
- manifests verified;
- packs verified;
- objects visited;
- indexes rebuilt;
- projections rebuilt;
- staged transactions cleaned;
- orphaned packs found;
- prune candidates found;
- bytes eligible for pruning;
- bytes deleted;
- repacked bytes;
- failures by phase and backend.

Reports should include repository id, run id, task type, policy version, dry-run
flag, actions, skipped actions, and errors.

Do not log object contents, credentials, raw pack payloads, or hidden ref names
visible only to restricted users.

## Implementation Phases

Phase 1: Maintenance report model.

Define task, policy, run, report, repair action, liveness state, and typed error
models. Add dry-run report generation without storage mutation.

Phase 2: Read-only local verification.

Scan native local repositories for metadata, refs, manifests, pack/index
checksums, staged transactions, and projection status.

Phase 3: Non-destructive local repair.

Abort stale staged transactions, rebuild missing indexes from valid packs, and
mark broken manifests hidden without deleting final object bytes.

Phase 4: Liveness scan.

Use ref snapshots and reachability service to classify reachable, recent
unreachable, expired unreachable, broken, and unknown objects.

Phase 5: Garbage collection dry-run.

Produce whole-pack prune candidates and mixed-pack reports without deleting data.

Phase 6: Safe whole-pack pruning.

Implement two-ref-snapshot safety, tombstone publication, final byte deletion,
and retryable failure handling for whole packs that contain no reachable objects.

Phase 7: Projection rebuild tasks.

Rebuild stale commit information projections from canonical refs and published
object storage.

Phase 8: S3 verification and repair.

Apply the same report and repair model to S3 keys, multipart uploads, manifests,
and tombstones.

Phase 9: Repacking.

Build replacement packs for live objects, publish them through the object store,
and tombstone old packs after validation.

Phase 10: Scheduler and administration.

Add scheduled maintenance, administrative dry-run/prune commands, metrics, and
policy configuration.

## Verification

Cover at least these cases:

- read-only verification reports a healthy repository without mutations;
- staged local transaction older than policy is reported in dry-run mode;
- stale staged transaction is cleaned in repair mode;
- valid pack with missing index rebuilds the index;
- manifest referencing missing pack is marked broken and hidden from readers;
- duplicate object ids across packs are reported deterministically;
- ref target missing from object storage is reported as repository corruption;
- liveness scan marks ref-reachable objects reachable;
- newly orphaned pack remains retained before retention expiry;
- expired orphaned whole pack becomes a prune candidate;
- unknown liveness caused by traversal limits is not pruned;
- ref snapshot change between scan and prune aborts deletion;
- whole-pack prune tombstones manifest before deleting pack and index bytes;
- failed delete leaves retryable maintenance state;
- mixed reachable/unreachable pack is not deleted before repacking support;
- projection rebuild publishes new projection only after successful rebuild;
- local interrupted publication is repairable after restart;
- S3 staged prefix cleanup is idempotent;
- S3 final manifest remains the visibility boundary during cleanup;
- repack preserves every reachable object id and updates read preference safely;
- production maintenance code has no JGit dependency.

## Open Questions

Should ordinary object lookup include orphaned published packs, or should
orphaned objects require an explicit maintenance/validation lookup mode?

Should pruning use tombstone manifests only, or keep a separate repository-level
deleted-pack log?

What is the default orphan retention period for failed receive-pack operations?

Should automatic startup repair ever hide broken manifests, or only report them
until an administrator approves repair?

How should maintenance expose administrative commands: CLI, HTTP admin endpoint,
or internal scheduled service only?

Should repacking create reachability summaries or bitmaps in the same operation,
or leave those indexes to a later optimization plan?

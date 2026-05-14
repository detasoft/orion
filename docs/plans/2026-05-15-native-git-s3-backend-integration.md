# Native Git S3 Backend Integration

## Goal

Add an end-to-end plan for Orion's `native-s3` Git repository backend.

The backend should implement `GitRepositoryProvider` and `GitRepository` using
S3-compatible storage adapters instead of JGit or local filesystem storage. It
should compose the native metadata, ref, object, pack, index, projection,
maintenance, and protocol services behind the same repository API used by
`native-file`.

The first target is correctness against MinIO and AWS S3-compatible APIs with
bounded retries, explicit consistency contracts, and clear unsupported-capability
behavior. Performance optimizations can come after local/native parity and S3
storage semantics are proven.

## Current State

The S3 pack storage client plan defines a primitive for streaming pack bytes to
S3, multipart upload, range reads, metadata, validation status, and cleanup of
temporary objects.

The object store and pack publication plan defines staged pack publication,
manifest visibility, final pack/index keys, and S3 repair diagnostics.

The ref storage plan defines an S3-compatible ref store using conditional writes,
ETag/version preconditions, prefix listing, and delete-if-match.

The commit projection plan defines S3 projection versions and active pointer
publication through conditional writes.

The repository metadata/provider plan defines S3 metadata layout and conditional
create semantics for native repositories.

The native repository backend plan says `native-s3` should be implemented by
swapping storage adapters under the native repository abstraction.

The current `S3GitRepositoryProvider` reports repository operations as
unsupported. Exploratory S3 repository classes are still JGit-shaped and should
not become the native backend boundary.

What is missing is a single integration plan that wires all S3 storage adapters
into a working native Git repository backend and defines consistency, retries,
capabilities, and rollout behavior.

## Non-Goals

Do not implement a generic S3 client from scratch in this plan. Use the
S3-compatible storage client boundary already planned.

Do not implement pack parsing, delta reconstruction, pack building, ref storage,
projection storage, or metadata storage here. This plan composes those
components and defines backend-level behavior.

Do not make S3 the first native backend. `native-file` remains the simpler
correctness target before `native-s3` is enabled.

Do not use JGit production APIs for S3 native repository operations.

Do not silently fall back to local storage or JGit when `native-s3` is selected.
Unsupported operations must fail with typed capability errors.

Do not assume S3 directory rename or atomic multi-object transactions.

## Backend Shape

`native-s3` should use the same logical repository composition as `native-file`:

```text
NativeGitRepository
  S3GitRepositoryMetadataStore
  S3GitRefStore
  S3GitPackStore
  S3GitPackIndexStore
  S3GitObjectDirectory
  S3GitProjectionStore
  S3GitRepositoryEventStore
  S3GitMaintenanceStore
  S3GitRepositoryLocks
```

The repository API should not expose S3 keys or bucket details. Lower-level
adapters can expose S3 diagnostics internally for logs, metrics, and repair
reports.

## Key Layout

Use one stable repository prefix:

```text
repositories/<repo-id>/
  metadata/orion-repository.json
  refs/
  packs/
  projections/
  events/
  maintenance/
  locks/
  tmp/
```

Suggested detailed layout:

```text
metadata/
  orion-repository.json
refs/
  heads/<branch>.json
  tags/<tag>.json
  symbolic/HEAD.json
packs/
  <pack-id>.pack
  <pack-id>.idx
  <pack-id>.json
projections/
  active.json
  versions/<projection-id>/...
events/
  <date>/<event-id>.json
maintenance/
  reports/<run-id>.json
locks/
  <lock-name>.json
tmp/
  create/<create-id>/...
  pack-publication/<transaction-id>/...
  projection/<run-id>/...
```

Keys should use normalized repository ids, not raw caller input. Key escaping
must be deterministic and must prevent prefix traversal or accidental overlap
between repositories.

## Metadata and Repository Creation

`S3NativeGitRepositoryProvider.findOrCreate(...)` should use metadata as the
visibility boundary.

Create flow:

1. validate repository name;
2. compute repository id and key prefix;
3. check final metadata key;
4. write staged create metadata under `tmp/create/<create-id>/`;
5. initialize symbolic `HEAD` and any required empty ref metadata;
6. write final `metadata/orion-repository.json` with conditional create;
7. verify final metadata reads back as `ACTIVE`;
8. clean staged create keys asynchronously.

If final metadata conditional create fails because another creator won, provider
policy decides whether to open the existing repository or report a create
conflict.

Partial create state under `tmp/create/` is not an active repository. Maintenance
can clean it after retention.

## S3 Consistency Contract

Define the S3 features the backend requires.

Required:

- object put;
- object get;
- object head;
- object delete;
- prefix listing;
- range reads;
- multipart upload and abort for large packs;
- conditional create or equivalent compare-and-set for metadata and refs;
- provider checksum or ETag metadata exposed for diagnostics.

The backend should explicitly test MinIO and AWS S3 behavior for:

- read-after-write for new objects;
- overwrite visibility;
- delete visibility;
- conditional write failures;
- prefix listing delays;
- multipart abort behavior.

If a provider cannot support required conditional writes for refs and active
pointers, it must not advertise write capabilities.

## Ref Store Integration

Use the native S3 ref store.

Requirements:

- read refs by exact key;
- list refs by prefix for advertisement;
- read symbolic `HEAD`;
- create/update/delete refs with conditional writes;
- expose ETag/version metadata in read snapshots;
- reject stale updates deterministically;
- support multi-ref transactions only when implemented above single-ref CAS.

`saveFiles`, receive-pack, migration, and mirror import must rely on ref CAS and
must not emulate atomic updates through blind overwrites.

Hidden/internal refs should use explicit prefixes or metadata flags so upload
advertisement and access checks can filter them without scanning unrelated keys.

## Pack and Object Store Integration

Use S3 pack publication as the only way objects become visible.

Publication flow:

1. write incoming or generated pack to `tmp/pack-publication/<transaction-id>/`;
2. validate pack bytes and checksum;
3. reconstruct deltas and final object ids;
4. build pack index;
5. upload index and staged manifest;
6. copy or write final pack and index keys;
7. write final manifest last;
8. object directory readers list final manifests only.

Because S3 has no atomic rename, final manifest publication is the visibility
boundary. Readers must ignore staged keys.

Object lookup should use:

- final manifest list;
- `.idx` range or full reads as appropriate;
- pack range reads for entry data;
- parsed object caches keyed by object id and pack checksum.

## Projection Store Integration

Use S3 projection versions and active pointer semantics.

Rules:

- write immutable projection version keys first;
- validate metadata and record counts;
- update `projections/active.json` with conditional write;
- readers use one active projection id per query;
- stale or failed projection does not make canonical object reads fail;
- maintenance can rebuild and switch active pointer.

Projection storage should not reuse the repository metadata active pointer. It
has its own version and status lifecycle.

## Event and Reflog Integration

Use immutable S3 event records.

Shape:

```text
events/<date>/<event-id>.json
reflog/<ref-hash>/<timestamp>-<event-id>.json
```

Rules:

- use conditional create for event id;
- never update event objects in place;
- include repository id and source operation id;
- keep redacted audit/export records separate from internal event records if
  needed;
- allow maintenance to compact or summarize old records later.

Durable event write failure should follow the Git event policy. By default it
should not roll back a successful ref update, but should mark audit/reflog
delivery degraded.

## Repository Open

`find(...)` should:

1. validate repository name;
2. resolve repository id/prefix;
3. read final metadata key;
4. validate format version, hash algorithm, backend kind, and state;
5. initialize S3 metadata/ref/pack/index/projection/event stores;
6. create `NativeGitRepository` with S3-backed adapters;
7. expose capabilities based on implemented adapters and provider feature flags.

Missing metadata returns not found. Staged create metadata does not count.

Metadata in `REPAIR_NEEDED`, `MIGRATING`, or `DISABLED` state should not open for
normal callers unless explicit repair/admin options are used.

## Capability Model

Advertise capabilities conservatively.

Initial `native-s3` capabilities can be:

- can open repository metadata;
- can list/read refs;
- can read object manifests;
- can load files after object lookup is implemented;
- can publish packs after S3 publication is implemented;
- can update refs after conditional write behavior is proven;
- can save files after pack publication and ref CAS work together;
- can receive-pack only after incoming pack quarantine and ref updates pass;
- can upload-pack only after object enumeration and pack building work on S3;
- can rebuild projections after projection store is implemented;
- can run maintenance after repair/cleanup tasks exist.

Do not advertise `saveFiles`, receive-pack, or upload-pack until the complete
path is implemented and tested against both MinIO and AWS-compatible behavior.

## Native LoadFiles on S3

`loadFiles` should:

1. resolve ref through S3 ref store;
2. open object directory snapshot from final manifests;
3. load commit and tree objects through pack indexes and range reads;
4. resolve requested paths;
5. load bounded blob bytes;
6. return commit id as version.

Optimizations:

- cache fanout tables and parsed trees;
- batch metadata/range reads where possible;
- use projection for path metadata when current;
- avoid loading full packs for small file reads.

Correctness comes before batching. The first version can be slower if it is
bounded and deterministic.

## Native SaveFiles on S3

`saveFiles` should:

1. read ref snapshot and ETag/version;
2. load base commit/tree;
3. build blobs, trees, and commit;
4. build no-delta pack;
5. publish pack/index/manifest through S3 publication transaction;
6. verify new commit is readable from an object directory snapshot;
7. update ref with conditional write based on captured ref version;
8. mark pack referenced or orphaned;
9. emit events/projection notifications.

If ref CAS fails after pack publication, the branch must remain unchanged and the
pack remains valid but orphaned for maintenance.

If pack publication status is uncertain, do not update refs until repair or
verification confirms visibility.

## Native Receive-Pack on S3

Receive-pack must use quarantine-style S3 staging.

Flow:

1. parse commands and policy;
2. stage incoming pack under `tmp/pack-publication/<transaction-id>/`;
3. validate pack/deltas/index/object graph;
4. publish final pack, index, and manifest;
5. verify command new ids are visible;
6. update refs with conditional writes or a multi-ref transaction when supported;
7. send report-status;
8. emit events;
9. leave successful-but-unreferenced packs orphaned if ref updates fail.

Atomic push should not be advertised until multi-ref all-or-nothing behavior is
implemented on top of S3 conditional primitives.

## Native Upload-Pack on S3

Upload-pack should read only final manifests and current refs.

Flow:

1. capture ref snapshot;
2. capture object directory snapshot;
3. advertise visible refs;
4. validate wants and access;
5. enumerate reachable objects;
6. open objects through S3 pack indexes/range reads;
7. build response pack or reuse existing pack data where later optimization
   allows it;
8. stream response with side-band where negotiated.

The first S3 upload-pack implementation can build no-delta packs from canonical
objects. Reusing existing deltas and pack slices can come later.

## Temporary Storage

Large S3 operations need local or remote staging.

Local temporary files may be used for:

- incoming pack buffering before multipart upload when streaming validation
  requires replay;
- delta reconstruction spill;
- pack building before multipart upload;
- index building;
- projection rebuild staging.

Policy:

- configured temp directory;
- size limits;
- cleanup on success/failure;
- no secrets in filenames;
- maintenance cleanup for abandoned temp files.

Remote temporary keys under `tmp/` should have transaction ids and retention
metadata so maintenance can abort/clean them.

## Retry and Uncertain State

S3 operations can fail after the server accepted a write.

Classify failures:

- retryable read;
- retryable write before commit;
- conditional conflict;
- uncertain write result;
- permanent validation failure;
- provider capability failure.

For uncertain writes:

- verify by reading final key/checksum before proceeding;
- avoid ref updates until object publication is confirmed;
- record repair-needed metadata when final state cannot be determined;
- expose retryability in operation failures.

Do not hide repeated retries behind unbounded loops. Every retry policy should
have maximum attempts and total timeout.

## Locks

Prefer conditional writes over locks for normal operations.

Use locks for:

- repository creation when conditional metadata is insufficient;
- migration/cutover;
- maintenance tasks;
- projection rebuild coordination;
- destructive cleanup.

Lock records should include:

- lock name;
- owner id;
- operation id;
- created time;
- expiry time;
- heartbeat/update time;
- repository id.

Stale lock stealing must be conservative and should be an administrative or
maintenance action for destructive operations.

## Maintenance and Repair

S3 maintenance must handle:

- incomplete multipart uploads;
- staged transaction prefixes;
- final pack without final manifest;
- final manifest referencing missing pack/index;
- checksum mismatches;
- stale projection versions;
- orphaned packs;
- old event/reflog objects;
- tombstoned manifests and delayed deletes;
- lock records past expiry.

Repair should be idempotent. A missing key after a delete attempt should not be
treated as proof that every related key was deleted unless the repair report
confirms the full set.

## Security

Configuration must keep credentials out of repository metadata and event records.

Security requirements:

- credentials come from the location/secret resolver;
- logs redact bucket credentials and signed URLs;
- repository ids and keys do not include secrets;
- access policy remains above the repository backend;
- hidden refs are filtered before advertisement and audit export;
- S3 bucket/prefix permissions should be documented for least privilege.

The backend should not grant broader repository access just because the S3
credentials can read the bucket.

## Observability

Record metrics:

- S3 operation counts by type;
- retries by operation;
- conditional conflicts;
- uncertain write recoveries;
- multipart upload count and abort count;
- bytes uploaded/downloaded;
- range read count;
- object directory snapshot size;
- ref read/write latency;
- pack publication latency;
- projection active pointer switch latency;
- maintenance cleanup results.

Diagnostics should include bucket alias, key prefix, operation phase, repository
id, operation id, and retryability. Avoid logging credentials or object contents.

## Rollout Strategy

Roll out `native-s3` in stages:

1. metadata-only provider that can create/open repositories but reports most
   operations unsupported;
2. ref store read/write tests against MinIO;
3. pack publication and object lookup tests against MinIO;
4. native `loadFiles` against S3 fixtures;
5. native `saveFiles` against S3 fixtures;
6. maintenance repair tests for staged/partial data;
7. receive-pack fixture tests;
8. upload-pack fixture tests;
9. AWS S3 compatibility suite behind explicit integration configuration;
10. opt-in production rollout after migration and rollback behavior is defined.

Do not enable `native-s3` by default during initial rollout.

## Implementation Phases

Phase 1: S3 backend contract.

Define required S3 capabilities, key layout, repository prefix normalization, and
typed backend errors.

Phase 2: Metadata provider.

Implement S3 repository metadata read/create/open with conditional final metadata
publication and `HEAD` initialization.

Phase 3: S3 ref store integration.

Wire the S3 ref store into `NativeGitRepository`, prove conditional create,
update, delete, and listing behavior.

Phase 4: S3 pack publication.

Wire pack store, index store, manifest publication, multipart upload, and staged
cleanup into object store publication.

Phase 5: S3 object directory.

Implement manifest snapshots, pack index lookup, range reads, duplicate object
handling, and typed corrupt/missing storage errors.

Phase 6: Native `loadFiles`.

Load files through S3 refs, object directory snapshots, commit/tree parsers, path
resolver, and bounded blob reads.

Phase 7: Native `saveFiles`.

Build and publish packs to S3, verify readability, update refs conditionally, and
handle orphaned packs after CAS conflicts.

Phase 8: Projection store.

Wire S3 projection versions and active pointer updates. Add fallback to canonical
object reads.

Phase 9: Events and reflog.

Store immutable S3 event/reflog records and integrate with projection/mirror
consumers.

Phase 10: Receive-pack.

Use S3 quarantine publication and conditional ref updates for native
receive-pack.

Phase 11: Upload-pack.

Serve fetch/clone from S3 object snapshots using native object enumeration and
pack building.

Phase 12: Maintenance and repair.

Add S3 cleanup, repair, orphan retention, incomplete multipart abort, and
tombstone handling.

Phase 13: Compatibility and rollout.

Run MinIO and AWS-compatible integration suites, then expose `native-s3` as an
explicit backend option.

## Verification

Cover at least these cases:

- production `native-s3` backend code has no JGit dependency;
- repository name maps to safe deterministic S3 prefix;
- metadata conditional create handles concurrent creators;
- staged create state is not openable as active repository;
- `HEAD` symbolic target is initialized and readable;
- ref create/update/delete use conditional writes and reject stale versions;
- ref listing returns only visible refs under the repository prefix;
- pack publication writes final manifest only after pack and index exist;
- readers ignore staged pack publication keys;
- multipart upload abort runs on validation failure;
- object directory snapshot remains stable while another pack is published;
- exact object lookup uses pack index and S3 range reads;
- missing manifest target reports typed corruption;
- `loadFiles` returns the same bytes/version as native local fixtures;
- oversized blob read fails before unbounded memory growth;
- `saveFiles` publishes pack before conditional ref update;
- stale save ref update leaves branch unchanged and pack orphaned;
- receive-pack pack checksum failure updates no refs;
- receive-pack successful push can be fetched back through upload-pack;
- upload-pack reads only final manifests and visible refs;
- projection active pointer switch is conditional and visible only after version
  data exists;
- durable event records use immutable conditional create keys;
- maintenance detects and cleans stale staged prefixes;
- incomplete multipart uploads are aborted by maintenance;
- uncertain write result is verified before continuing or marked repair-needed;
- MinIO and AWS S3 compatibility tests pass for required semantics;
- unsupported capabilities are not advertised before their full path is ready.

## Open Questions

Which S3 client abstraction should own conditional write semantics: a generic
object client or Git-specific stores?

Should final pack publication copy staged objects to final keys, or upload final
keys directly and rely only on manifest visibility?

Should `native-s3` require AWS S3 strong consistency assumptions, or support
providers with weaker listing consistency through extra verification delays?

How much local temporary storage is acceptable for large receive-pack and
projection rebuild operations?

Should `native-s3` start as read-only after migration before allowing writes?

Should object directory snapshots store manifest ids in memory only, or persist
snapshot records for long-running upload-pack operations?

# JGit to Native Git Migration and Rollout

## Goal

Add a detailed plan for moving repositories and workflows from the existing
JGit-backed storage path to Orion's native Git backend.

The migration should let Orion keep `jgit-file` as the compatibility backend,
introduce `native-file` safely, import existing bare JGit repositories into the
native layout, validate parity before switching callers, and provide rollback or
fallback paths when native behavior is not ready for a workflow.

The first target is local file storage. S3/native migration can reuse the same
validation and rollout model after native S3 stores are implemented.

## Current State

`FileGitRepositoryProvider` currently opens bare repositories with JGit and
returns `JGitRepository`.

The native repository backend plan says native and JGit-backed repositories
should run side by side, `jgit-file` should remain the default, and import or
migration tooling should come only after native storage stabilizes.

The native repository metadata/provider plan explicitly rejects silently opening
JGit bare repositories as native storage and leaves actual migration to a
separate plan.

The native load/save/upload/receive plans define operation-level parity targets,
but there is no plan for repository import, parity certification, staged rollout,
configuration switching, rollback, or operational diagnostics.

Without a migration plan, native code can be implemented but existing
installations will not have a safe path to move repositories off JGit-backed
storage.

## Non-Goals

Do not implement native pack parsing, ref storage, object publication, or
repository metadata in this plan. This plan orchestrates those components.

Do not remove `JGitRepository` immediately.

Do not mutate a JGit repository in place during the first migration
implementation. Prefer copy/import into a native repository root.

Do not make native storage the default until selected workflows have parity
tests and operational fallback.

Do not migrate remote Git repositories owned by external providers. This plan is
for Orion-managed repository storage.

Do not depend on JGit in native production runtime paths. Migration tooling may
use JGit as a source reader during the transition, but native repository
operations after migration should not.

## Migration Principles

Keep the migration conservative:

- never guess repository format from directory shape alone;
- require explicit source and target backend selection;
- keep source JGit repository read-only during import or hold a lock;
- import into a separate native target;
- validate refs, objects, file snapshots, and protocol behavior before cutover;
- switch configuration or routing only after validation succeeds;
- retain the source repository until rollback retention expires;
- record migration metadata and reports.

Migration should be repeatable. A failed import should be safely cleaned up or
resumed without corrupting the source repository.

## Service Boundary

Introduce migration-facing services:

- `GitRepositoryMigrationService`;
- `GitRepositoryImportService`;
- `GitRepositoryParityVerifier`;
- `GitRepositoryMigrationPlanner`;
- `GitRepositoryMigrationReportStore`;
- `GitRepositoryCutoverService`;
- `GitRepositoryRollbackService`;
- `GitRepositoryMigrationLock`;
- `GitRepositoryMigrationPolicy`.

Value objects:

- `GitRepositoryMigrationRequest`;
- `GitRepositoryMigrationPlan`;
- `GitRepositoryMigrationRun`;
- `GitRepositoryMigrationReport`;
- `GitRepositoryMigrationStep`;
- `GitRepositoryMigrationFailure`;
- `GitRepositoryParityCheck`;
- `GitRepositoryCutoverDecision`;
- `GitRepositoryRollbackPlan`.

The service should support dry-run, import-only, verify-only, cutover, and
rollback modes.

## Source and Target Model

Represent source and target explicitly.

Source:

- backend kind: `jgit-file`;
- repository name;
- storage path;
- expected source state;
- optional source lock policy;
- source read snapshot metadata.

Target:

- backend kind: `native-file`;
- repository name;
- target storage path;
- native metadata format version;
- hash algorithm;
- default branch and `HEAD` policy;
- target create policy: fail if exists, reuse incomplete import, or overwrite
  only in explicit cleanup mode.

Never let a migration request use the same path as both source and target unless
a later in-place migration mode is designed and tested separately.

## Migration Metadata

Record migration state outside normal repository metadata and optionally inside
the target native metadata.

Fields:

- migration id;
- source backend;
- source repository name and path;
- target backend;
- target repository id and path;
- started time;
- completed time;
- state;
- migration tool version;
- source ref snapshot id;
- target metadata version;
- object count imported;
- pack count imported;
- refs imported;
- parity checks run;
- cutover decision;
- rollback retention deadline;
- failure reason.

States:

- `PLANNED`;
- `IMPORTING`;
- `VERIFYING`;
- `READY_FOR_CUTOVER`;
- `CUTOVER_COMPLETE`;
- `ROLLBACK_REQUESTED`;
- `ROLLED_BACK`;
- `FAILED`;
- `ABORTED`.

## Source Quiescence

Define how source writes are handled during migration.

Options:

- offline migration: stop writes to the source repository during import and
  verification;
- read-only lock: allow reads but reject writes during import;
- incremental migration: import current state, then replay later ref/object
  changes before cutover.

The first implementation should use offline or read-only migration. Incremental
change replay can come later after native eventing and ref/object import are
stable.

Receive-pack, save-files, and any direct repository writes must not update the
source while the migration expects a stable source snapshot.

## Discovery and Planning

Migration planning should inspect the source and produce a dry-run report.

Checks:

- repository path exists;
- source is a JGit-compatible bare repository;
- source is not already native metadata;
- target path does not contain active native metadata unless resume is requested;
- source object database is readable;
- refs and `HEAD` are readable;
- hash algorithm is SHA-1;
- repository size is within configured migration limits;
- target storage has enough space when this can be estimated;
- selected native capabilities are implemented.

The dry-run should list expected refs, object counts when cheaply available,
pack files, loose objects, and unsupported features.

## Import Strategy

The migration can import by reading canonical Git objects and refs from the JGit
source and writing native storage.

Preferred phases:

1. create native target metadata in `IMPORTING` state;
2. capture source ref snapshot;
3. enumerate source packs and loose objects;
4. import pack files where valid and compatible;
5. import loose objects by building native packs;
6. build native pack indexes;
7. reconstruct deltas as needed for validation and object ids;
8. publish pack manifests;
9. import refs and symbolic `HEAD`;
10. rebuild commit projection;
11. mark target metadata `ACTIVE` only after validation.

Where possible, preserve pack bytes exactly. If the source has loose objects or
pack features the first native storage layer cannot use directly, build new
native packs from canonical object records.

## Pack Import

For each source pack:

- read pack bytes;
- validate pack header and trailer checksum;
- parse entries through native pack parser;
- reconstruct deltas if needed;
- compute final object ids;
- build or verify pack index;
- publish pack and index through native object store publication;
- record source pack checksum and target pack id.

If source `.idx` files exist, they can be used as fixtures or validation input,
but native index building should not blindly trust them. Native storage should
publish only packs that pass its own validation.

## Loose Object Import

Existing JGit repositories may contain loose objects.

Import flow:

1. enumerate loose object ids;
2. read and inflate loose object content;
3. validate canonical object id;
4. group objects into one or more native packs;
5. build pack indexes and manifests;
6. publish through object store publication.

Loose object import should enforce maximum object size and total migration
limits. Invalid loose objects should fail migration unless policy allows
quarantine and report-only mode.

## Ref Import

Import refs after objects are published and readable.

Refs:

- branch refs;
- tag refs;
- symbolic `HEAD`;
- peeled tag metadata if available and valid;
- optional internal refs only if migration policy includes them.

Flow:

1. read source ref snapshot;
2. validate ref names;
3. verify target object ids exist in native object storage;
4. initialize native ref store;
5. write refs with expected empty state;
6. write symbolic `HEAD`;
7. verify native ref snapshot matches source snapshot.

If source refs change between initial snapshot and final verification, abort or
restart unless incremental migration mode is enabled.

## Projection Rebuild

After object and ref import, rebuild native projections from canonical native
storage.

Projection tasks:

- parse commits, trees, and tags;
- build parent/generation data;
- build path indexes required by native `loadFiles`;
- write ref snapshot projection;
- publish projection version atomically;
- validate sample queries against canonical tree walks.

Projection rebuild failure should block cutover only for workflows that require
projection acceleration. For the first rollout, native canonical reads should be
able to operate without projection.

## Parity Verification

Before cutover, compare source and target behavior.

Required checks:

- metadata and repository identity are valid;
- `HEAD` symbolic target matches;
- visible refs match;
- branch and tag object ids match;
- annotated tag peeling matches;
- every source ref target exists in native storage;
- reachable commit graph from refs matches;
- selected file snapshots match for configured paths;
- object counts by type match for reachable objects;
- native pack indexes can locate imported objects;
- native `loadFiles` matches JGit for fixtures;
- native `saveFiles` on a disposable copy behaves like JGit for deterministic
  inputs;
- native upload-pack can serve a clone/fetch fixture from the migrated target;
- native receive-pack can push into a disposable migrated copy when enabled.

Parity reports should distinguish blockers, warnings, and checks skipped because
a native capability is not implemented yet.

## Cutover

Cutover should be explicit.

Possible cutover mechanisms:

- configuration switch from `jgit-file` to `native-file`;
- location resolver route update for selected repositories;
- repository registry entry pointing a logical name at native backend;
- feature flag enabling native operations only for selected workflows.

Cutover flow:

1. confirm migration report is `READY_FOR_CUTOVER`;
2. verify source has not changed since parity check;
3. disable or drain source writes;
4. update routing/configuration atomically where possible;
5. open target through native provider;
6. perform smoke reads;
7. mark migration `CUTOVER_COMPLETE`;
8. keep source repository read-only until rollback retention expires.

Do not delete the source repository during cutover.

## Rollback

Rollback should be possible while the source repository is retained and native
writes have not diverged beyond policy.

Rollback modes:

- no-write rollback: switch routing back to source JGit repository;
- replay rollback: export native writes back to JGit source before switching;
- abort rollback: fail if native repository accepted writes after cutover.

The first rollout should use no-write rollback or require write freeze during the
initial validation window. Exporting native writes back to JGit is a later plan.

Rollback flow:

1. mark migration `ROLLBACK_REQUESTED`;
2. stop native writes;
3. verify source repository is still available;
4. switch routing/configuration back to `jgit-file`;
5. run smoke reads;
6. mark `ROLLED_BACK`;
7. keep native target for diagnostics until cleanup policy expires.

## Rollout Strategy

Roll out by workflow and backend capability.

Suggested order:

1. create new empty native local repositories behind explicit config;
2. native `loadFiles` for controlled versioned storage fixtures;
3. native `saveFiles` for internal small-file writes;
4. migrate one disposable JGit fixture repository and compare reads;
5. enable native upload-pack for migrated read-only repositories;
6. enable native receive-pack for migrated test repositories;
7. migrate low-risk internal repositories;
8. broaden parity suite and operational monitoring;
9. make native backend opt-in for production repositories;
10. consider default change only after rollback and maintenance are proven.

Each stage should have exit criteria and a rollback decision.

## Safety Policies

Add policy controls:

- allow migration for selected repository names only;
- require offline migration;
- require source write lock;
- require full reachable object verification;
- require upload-pack parity before read-only cutover;
- require receive-pack parity before write cutover;
- require maintenance healthy status;
- retain source for configured duration;
- disallow automatic cutover after warnings;
- require administrator approval for destructive cleanup.

Defaults should favor dry-run and manual cutover.

## Cleanup

After rollback retention expires and native operation is stable:

- archive migration report;
- optionally archive source repository path;
- remove source repository only with explicit administrative approval;
- remove failed target imports after diagnostics retention;
- compact native storage after migration if imported packs are inefficient;
- remove old inactive projection versions through maintenance.

Cleanup should never run as part of initial import or cutover by default.

## S3 and Cross-Backend Migration

After native S3 stores exist, the same migration service can support:

- JGit file to native S3;
- native file to native S3;
- native S3 to native file for export/backup;
- JGit file to native file to native S3 staged migration.

Cross-backend migration should reuse:

- pack import validation;
- ref snapshot import;
- projection rebuild;
- parity verification;
- cutover/rollback reports.

S3 migration must account for conditional writes, multipart uploads, final
manifest visibility, and eventually consistent cleanup.

## Error Model

Use typed failures:

- invalid migration request;
- source repository missing;
- source is not JGit bare repository;
- source changed during migration;
- source lock unavailable;
- target already exists;
- target create failed;
- unsupported source hash algorithm;
- source object read failure;
- source ref read failure;
- pack validation failure;
- loose object validation failure;
- native publication failure;
- native ref import failure;
- projection rebuild failure;
- parity mismatch;
- cutover precondition failed;
- rollback unavailable;
- cleanup failed.

Every failure should identify the migration phase, repository name, source and
target backend kinds, and retryability.

## Observability

Record:

- migration run count;
- dry-run count;
- import duration;
- verification duration;
- cutover duration;
- rollback count;
- source pack count;
- source loose object count;
- imported object count;
- imported bytes;
- ref count;
- parity mismatches by type;
- skipped checks;
- failed phase;
- rollback retention deadline.

Migration reports should be human-readable and machine-readable.

Do not log credentials, object contents, or hidden refs in user-visible reports.

## Implementation Phases

Phase 1: Migration request and report model.

Define request, plan, run, report, step, state, parity check, failure, and policy
models.

Phase 2: Dry-run discovery.

Inspect JGit source repositories and native target locations without writing
target data. Report source refs, packs, loose objects, and unsupported features.

Phase 3: Native target create in importing state.

Create native metadata/layout with migration id and `IMPORTING` state. Ensure
normal providers do not open it as active.

Phase 4: Pack import.

Import and validate source pack files through native pack parser, index builder,
and object publication service.

Phase 5: Loose object import.

Read loose objects, validate canonical ids, group them into native packs, and
publish them.

Phase 6: Ref and HEAD import.

Write native refs and symbolic `HEAD` after verifying imported objects exist.

Phase 7: Projection rebuild.

Rebuild commit projection from the imported native repository and report status.

Phase 8: Parity verifier.

Compare refs, object reachability, selected file snapshots, and protocol fixtures
between source and target.

Phase 9: Cutover.

Add explicit cutover command or service operation that switches routing only
after a passing migration report and final source unchanged check.

Phase 10: Rollback.

Implement no-write rollback by switching routing back to source while source
retention is active.

Phase 11: Cleanup and retention.

Add administrative cleanup for failed imports, old native targets, and retained
source repositories.

Phase 12: S3 target support.

Reuse the migration flow with native S3 metadata, pack, index, ref, and
projection stores once they are implemented.

## Verification

Cover at least these cases:

- dry-run reports source refs, packs, loose objects, and target availability;
- invalid source repository fails before creating target data;
- target already exists fails unless resume policy allows it;
- migration target in `IMPORTING` state is not opened by normal native provider;
- valid source pack imports into native object store with matching object ids;
- loose blob, tree, commit, and tag objects import into native packs;
- invalid source pack aborts migration without active target;
- invalid loose object aborts migration unless report-only policy allows it;
- imported refs match source refs exactly;
- symbolic `HEAD` target matches source;
- source ref change during migration aborts or restarts according to policy;
- projection rebuild from imported objects succeeds for a simple history;
- native `loadFiles` returns the same bytes and version as JGit source;
- reachable object counts by type match for linear and merge histories;
- annotated tag peeling matches source behavior;
- native upload-pack fixture can fetch from imported target;
- cutover refuses to run with parity blockers;
- cutover succeeds only when final source snapshot matches verified snapshot;
- rollback switches routing back to retained source in no-write mode;
- native target remains available for diagnostics after rollback;
- cleanup does not delete source before retention expiry;
- migration tooling production runtime paths do not make native repository
  operations depend on JGit.

## Open Questions

Should migration tooling use JGit as a source reader initially, or read JGit bare
storage directly through native object/ref parsers from the start?

Should imported packs preserve source pack bytes exactly, or should Orion repack
everything into native-generated packs for a cleaner storage model?

How should repositories with many loose objects be batched into packs during
migration?

Should cutover be configuration-file based, repository-registry based, or managed
through the generic location resolver?

What is the minimum parity suite required before enabling write operations on a
migrated repository?

Should rollback support replaying native writes back to JGit, or should the first
production rollout require a write freeze during the rollback window?

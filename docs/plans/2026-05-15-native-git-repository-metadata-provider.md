# Native Git Repository Metadata and Provider

## Goal

Add a detailed plan for native Git repository creation, opening, metadata, layout,
capabilities, and provider integration.

This is the bootstrap layer for native repositories. It should let Orion resolve
`GitRepositoryProvider.find(...)` and `findOrCreate(...)` without JGit, create a
valid native repository atomically, initialize repository metadata and `HEAD`,
open existing native repositories safely, and expose backend capabilities for
rollout.

The first implementation should target a local native backend. The contract must
also fit S3-compatible storage so `native-s3` can later use the same repository
metadata and provider behavior with different storage adapters.

## Current State

`GitRepositoryProvider` exposes three operations:

- `exists(repositoryName)`;
- `find(repositoryName)`;
- `findOrCreate(repositoryName)`.

The current file provider validates repository names, resolves them under the
configured storage directory, creates bare JGit repositories, caches repository
handles, and returns `JGitRepository`.

`S3GitRepositoryProvider` currently reports repository operations as unsupported.

The native repository backend plan defines the high-level idea of
`NativeGitRepository`, native metadata, local layout, and backend capabilities.
It does not define the exact metadata record, atomic create/open flow, provider
cache behavior, format version handling, or local/S3 differences.

The new location/resource resolution work is adding a more general resolver
layer. Native Git provider integration should fit that resolver, but this plan
focuses on Git repository metadata and provider behavior, not the generic
location resolver itself.

## Non-Goals

Do not implement `loadFiles`, `saveFiles`, upload-pack, receive-pack, pack
storage, ref storage, or projections in this plan.

Do not migrate existing JGit repositories to native format in the first provider
step.

Do not silently open a JGit bare repository as native storage or a native
repository as JGit storage.

Do not make S3 the first required backend. Design the metadata and provider
contract for S3, but implement local native storage first.

Do not expose backend-specific filesystem paths or S3 keys through
`GitRepository`.

Do not depend on JGit in production code for the native provider.

## Provider Boundary

Introduce native provider classes:

- `NativeGitRepositoryProvider`;
- `NativeGitRepositoryFactory`;
- `NativeGitRepositoryMetadataStore`;
- `NativeGitRepositoryLayout`;
- `NativeGitRepositoryHandleCache`;
- `NativeGitRepositoryOpenOptions`;
- `NativeGitRepositoryCreateOptions`;
- `NativeGitRepositoryCapabilities`.

Backend-specific adapters:

- `LocalNativeGitRepositoryProvider`;
- `LocalNativeGitRepositoryMetadataStore`;
- `S3NativeGitRepositoryProvider`;
- `S3NativeGitRepositoryMetadataStore`.

The provider owns:

- repository name validation;
- location resolution;
- create/open lifecycle;
- metadata read/write;
- repository handle caching;
- capability exposure;
- shutdown cleanup.

`NativeGitRepository` owns repository operations after open. Lower-level stores
own refs, packs, indexes, object directory snapshots, projections, and locks.

## Repository Name Validation

Native validation should be at least as strict as the current file provider.

Rules:

- repository name must not be null, empty, or blank;
- repository name must be relative;
- repository name must not resolve to the storage root;
- path segments must not be blank, `.`, or `..`;
- normalized path must remain under the configured storage root or repository
  prefix;
- NUL bytes are rejected;
- platform separators are normalized deliberately;
- names should preserve case but collision policy should be defined per backend;
- optional `.git` suffix is accepted or rejected by explicit policy.

Validation should produce typed failures that map to `Result.FailureCode.GENERAL`
or a more specific future code, not generic null pointer or path exceptions.

## Repository Identity

Separate user input from repository identity.

Fields:

- `requestedName`: original caller input after basic trimming policy;
- `normalizedName`: stable provider-relative name;
- `repositoryId`: durable internal id;
- `storagePath` or storage prefix;
- display name or description when supported.

For local storage, `repositoryId` can initially be the normalized name. For S3 and
future migration, a generated id may be useful to avoid key layout coupling.

The metadata record should store both normalized name and repository id so future
renames or migration can be explicit.

## Metadata Record

Add a durable repository metadata record, for example
`orion-repository.json`.

Fields:

- repository id;
- normalized repository name;
- storage format version;
- metadata schema version;
- hash algorithm: initially `sha1`;
- default branch: initially `refs/heads/master` or configured default;
- `HEAD` mode: symbolic or detached if ever supported;
- object storage kind: local-pack, s3-pack;
- ref storage kind: local-files, s3-conditional;
- pack index kind;
- projection storage kind;
- created time;
- updated time;
- last opened time when tracked;
- last maintenance time;
- feature flags;
- backend capabilities;
- repository state: creating, active, repair-needed, disabled;
- optional migration source;
- optional format upgrade history.

Metadata should avoid storing credentials, access tokens, private key paths, or
other secrets.

## Format Versioning

Use explicit format versions.

Version checks:

- missing metadata means "not a native repository";
- unknown newer version fails with unsupported format;
- known older version fails with migration-required unless an in-place migration
  is explicitly implemented;
- incompatible hash algorithm fails before opening object stores;
- incompatible backend kind fails before opening lower-level adapters.

Do not infer native format from directory names alone. Metadata is the authority.

## Repository States

Represent lifecycle state explicitly:

- `CREATING`: metadata or storage directories are being created;
- `ACTIVE`: repository can be opened normally;
- `REPAIR_NEEDED`: maintenance must inspect before normal open;
- `DISABLED`: administrative block;
- `MIGRATING`: migration or format upgrade is in progress;
- `FAILED_CREATE`: previous create failed and cleanup/repair is needed.

Normal `find(...)` should return success only for `ACTIVE` repositories unless
caller options allow repair mode.

`findOrCreate(...)` should not treat `FAILED_CREATE` as a valid repository.

## Local Layout

Use the local native layout from the backend plan, with metadata as the
visibility boundary:

```text
<repo>/
  orion-repository.json
  refs/
  packs/
  projections/
  tmp/
  locks/
  maintenance/
```

Subdirectories may contain:

```text
refs/
  heads/
  tags/
  symbolic/
packs/
  <pack-id>.pack
  <pack-id>.idx
  <pack-id>.json
projections/
  active.json
  versions/
tmp/
  create-<id>/
  pack-publication/
locks/
  repository.lock
  maintenance.lock
maintenance/
  reports/
```

The first provider phase can create only the directories needed immediately, but
metadata should describe the full intended layout.

## Local Create Flow

Create must be atomic from the perspective of `find(...)`.

Suggested flow:

1. validate repository name;
2. compute normalized storage path;
3. fail if a valid native metadata file already exists;
4. fail if a JGit-style repository exists at that path unless explicit migration
   mode is requested;
5. create a temporary create directory under the parent or storage `tmp`;
6. write metadata with state `CREATING`;
7. create required subdirectories;
8. initialize ref storage and symbolic `HEAD`;
9. write any initial empty object/index/projection metadata required by stores;
10. fsync or flush according to local durability policy;
11. publish the repository directory or final metadata atomically;
12. update metadata state to `ACTIVE`;
13. open `NativeGitRepository`.

If any step fails before `ACTIVE`, `find(...)` should not return a usable
repository. Maintenance can later clean `CREATING` or `FAILED_CREATE` remnants.

## S3 Create Flow

S3 cannot rename directories atomically, so final metadata publication should be
the visibility boundary.

Suggested keys:

```text
repositories/<repo>/metadata/orion-repository.json
repositories/<repo>/refs/...
repositories/<repo>/packs/...
repositories/<repo>/projections/...
repositories/<repo>/tmp/create/<create-id>/...
```

Flow:

1. validate repository name;
2. compute normalized key prefix;
3. check final metadata key does not already exist;
4. write staged metadata under create prefix with state `CREATING`;
5. initialize staged or final empty ref records as required;
6. write final metadata with conditional create-if-absent;
7. verify final metadata reads back and state is `ACTIVE`;
8. clean staged create prefix asynchronously.

If conditional final metadata write fails because another creator won, open the
winning repository or return conflict according to provider policy.

## HEAD Initialization

Initialize `HEAD` consistently.

Policy fields:

- default branch ref name;
- symbolic `HEAD` target;
- whether first push/save to a non-default branch can move `HEAD`;
- unborn `HEAD` representation.

Default:

- `HEAD` is symbolic;
- target is configured default branch;
- default branch may be `refs/heads/master` for compatibility or a configurable
  value such as `refs/heads/main`;
- repository starts with no branch ref object target until first write/push.

Upload-pack and receive-pack plans rely on empty repository advertisement and
first push behavior, so `HEAD` must be readable immediately after creation.

## Capability Model

Expose backend capabilities before callers attempt operations.

Suggested capabilities:

- native format version;
- hash algorithm;
- can list refs;
- can read refs atomically;
- can update refs atomically;
- can read objects;
- can publish packs;
- can load files;
- can save files;
- can serve upload-pack;
- can receive-pack;
- can rebuild projections;
- has projection acceleration;
- has maintenance support;
- supports S3-compatible storage;
- supports multi-ref transactions;
- supports repair mode.

`GitRepository` may grow capability methods later. Until then, provider-specific
services can expose capabilities internally and unsupported public operations can
fail with typed `GitOperationException`.

## Open Flow

`find(...)` should:

1. validate repository name;
2. resolve storage location;
3. read metadata;
4. verify metadata version and backend kind;
5. verify repository state is openable;
6. initialize lower-level stores from metadata;
7. open a `NativeGitRepository`;
8. cache or return the handle according to cache policy.

Missing metadata returns `NOT_FOUND`.

Metadata corruption returns a typed `GENERAL` failure with repair diagnostics.

Unsupported format returns `NOT_SUPPORTED` or a specific future failure code.

## Exists Semantics

`exists(...)` should answer whether a valid openable repository exists.

For local storage:

- return false for missing path;
- return false for path without native metadata;
- return false for incomplete create state unless repair mode says otherwise;
- return false for invalid names;
- return true for active native metadata.

For S3:

- return true only when final metadata key exists and is valid enough to identify
  an active repository;
- avoid listing whole prefixes when a direct metadata read is enough.

This differs from "any directory exists" and prevents partial creates from
appearing valid.

## Handle Cache

Define repository handle caching explicitly.

Cache key:

- normalized repository name;
- backend kind;
- metadata version or repository id.

Cache policy:

- cache open handles when lower-level stores are safe to reuse;
- do not cache failed opens forever;
- invalidate cache when metadata state changes to disabled/migrating;
- close handles during application shutdown;
- avoid returning a closed handle after caller `close()` if handles are shared.

The current `GitRepository` extends `AutoCloseable`. If provider caches shared
handles, `close()` semantics must be clear:

- either return lightweight wrappers whose close releases caller lease only;
- or do not cache `GitRepository` instances directly and cache lower-level
  stores instead.

This should be decided before native provider rollout.

## Repository Locks

Repository creation/open should coordinate with maintenance and migration.

Local:

- use create lock or atomic directory/metadata creation;
- use repository lock for format migration;
- detect stale create locks conservatively.

S3:

- use conditional metadata writes for create;
- use lock records only for maintenance/migration where needed;
- include owner id and expiry for repair diagnostics.

Normal `loadFiles` and `upload-pack` should not require a repository-wide lock
after open.

## Coexistence With JGit Provider

Native and JGit providers must coexist.

Rules:

- `jgit-file` remains default until native workflows reach parity;
- `native-file` uses native metadata and layout;
- `jgit-file` should not open a native repository path as a JGit bare repo;
- `native-file` should not open a JGit bare repo without explicit migration;
- configuration selects backend intentionally;
- tests cover both providers side by side under different storage roots.

If both providers share a root during migration, repository format detection must
be explicit and conservative.

## Location Resolver Integration

The provider should fit the generic location/resource resolver work.

Native provider registration should expose:

- supported scheme or location kind;
- required phase: early enough for ACL/versioned storage;
- provided type: `GitRepositoryProvider`;
- capabilities: JGit file, native file, native S3 where available;
- typed failure for unsupported combinations.

This plan should not own the generic resolver model, but Git provider code should
avoid hard-coded scheme switches once the resolver is available.

## Configuration

Support explicit backend selection.

Examples:

```text
git.storage.backend = jgit-file
git.storage.backend = native-file
git.storage.backend = native-s3
```

or location-based equivalents:

```text
file:/var/lib/orion/git?kind=native-git
s3://bucket/orion/git?kind=native-git
```

Configuration should define:

- storage root or prefix;
- default branch;
- hash algorithm;
- native format version policy;
- create-on-push behavior;
- handle cache policy;
- repair-on-open policy;
- feature flags for load/save/upload/receive.

## Failure Model

Use typed failures:

- invalid repository name;
- repository not found;
- repository already exists;
- path escapes storage root;
- native metadata missing;
- metadata corrupt;
- unsupported metadata version;
- unsupported hash algorithm;
- unsupported backend kind;
- repository disabled;
- repository repair needed;
- create conflict;
- create failed;
- open failed;
- cache closed;
- storage read failure;
- storage write failure.

Map these through the current `Result<GitRepository>` API without losing
diagnostic detail.

## Migration Boundary

This provider should prepare for migration but not implement it first.

Migration requirements later:

- detect JGit bare repository;
- import refs and objects into native stores;
- build native metadata;
- rebuild pack indexes/projections;
- validate parity with JGit reads;
- switch provider configuration only after successful validation;
- keep rollback path.

Until that plan is implemented, native open should fail clearly when it sees a
JGit repository without native metadata.

## Observability

Record:

- provider backend selected;
- repository open/create count;
- create duration;
- open duration;
- metadata version;
- repository state;
- capability set;
- cache hit/miss;
- invalid repository name count;
- failed open/create reasons;
- repair-needed repositories.

Logs should include repository normalized name and backend kind, but not secrets
or raw S3 credentials.

## Implementation Phases

Phase 1: Metadata model and validation.

Define repository metadata, format version, state, capabilities, hash algorithm,
backend kind, and validation errors.

Phase 2: Repository name normalization.

Extract shared name validation and path/prefix resolution for native providers.
Cover unsafe path segments and storage-root escapes.

Phase 3: Local metadata store.

Read/write local `orion-repository.json`, validate version/state, and detect
missing/corrupt metadata.

Phase 4: Local create flow.

Create native repository directories, metadata, ref storage, and symbolic `HEAD`
atomically enough that `find(...)` never opens partial creates.

Phase 5: Local open flow.

Open existing native repositories, compose lower-level store placeholders or real
stores, and return `NativeGitRepository` with unsupported operations failing
clearly until implemented.

Phase 6: Provider cache and lifecycle.

Define cached handle/lease semantics, close behavior, shutdown cleanup, and
failed-open retry behavior.

Phase 7: Capability exposure.

Expose native backend capabilities to internal callers and map unsupported public
operations to typed failures.

Phase 8: Resolver/configuration integration.

Register `native-file` alongside `jgit-file` through the active provider
resolution mechanism without hard-coded fallback to JGit.

Phase 9: S3 metadata store.

Implement S3 metadata read/write and conditional create semantics behind the same
metadata contract, while repository operations can still report unsupported until
stores are ready.

Phase 10: Repair and maintenance hooks.

Expose `REPAIR_NEEDED`, incomplete create cleanup hooks, and metadata checks to
the maintenance service.

Phase 11: Migration readiness.

Add explicit failures and diagnostics for JGit repository paths and unsupported
native format versions. Leave actual migration to a separate plan.

## Verification

Cover at least these cases:

- production native provider code has no JGit dependency;
- valid repository names normalize deterministically;
- null, blank, absolute, `.`, `..`, and storage-root-escaping names are rejected;
- `exists(...)` returns false for invalid names and partial creates;
- `find(...)` returns not found for missing metadata;
- local `findOrCreate(...)` creates metadata, layout directories, and symbolic
  `HEAD`;
- failed create does not become visible as an active repository;
- opening an active native repository after restart succeeds;
- corrupt metadata produces a typed open failure;
- unsupported newer format version fails clearly;
- unsupported hash algorithm fails before stores are opened;
- JGit bare repository without native metadata is not opened as native;
- native repository path is not opened by JGit provider in native tests;
- handle cache returns usable handles and does not return closed handles;
- provider shutdown closes cached resources;
- default branch and `HEAD` target are persisted;
- capabilities report unsupported operations before lower-level services are
  implemented;
- resolver/configuration can select `jgit-file` and `native-file` explicitly;
- S3 metadata conditional create handles concurrent creators deterministically;
- repair-needed metadata state is reported and not opened as active;
- maintenance can detect incomplete create state;
- native and JGit providers can coexist in the same runtime configuration.

## Open Questions

Should the default branch remain `refs/heads/master` for compatibility, or move
to a configurable default such as `refs/heads/main` before native repositories
become visible?

Should provider caching cache `GitRepository` handles directly or cache
lower-level stores and return per-call repository wrappers?

Should native local layout live beside existing JGit repositories under the same
root, or under a separate root to prevent accidental format confusion?

Should `exists(...)` report true for `REPAIR_NEEDED` repositories, or only for
repositories openable by normal callers?

Should metadata be JSON for inspectability or a compact record managed by the
storage layer?

Should create initialize empty pack/projection metadata immediately, or only
repository metadata, refs, and directories?

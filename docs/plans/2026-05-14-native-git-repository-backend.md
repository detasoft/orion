# Native Git Repository Backend

## Goal

Add a JGit-free repository backend that implements Orion's `GitRepository`
boundary by composing the native pack, object, index, ref, and protocol layers.

This backend is the integration point for the lower-level plans. It should let
Orion open, create, read, update, fetch from, and receive into repositories
without importing repository state through JGit.

## Current State

`GitRepository` is already the backend-independent interface used by Orion
services. It defines upload, receive, file loading, file saving, fetch access
checks, and a narrow unwrap escape hatch.

The current file repository provider opens bare repositories with JGit
`FileRepositoryBuilder` and returns `JGitRepository`. `JGitRepository` delegates
upload and receive to JGit `UploadPack` and `ReceivePack`, loads files by walking
JGit commits and trees, saves files with JGit object insertion, and updates refs
with JGit `RefUpdate`.

The S3 repository provider currently reports repository operations as unsupported.
The exploratory S3 repository classes are JGit-shaped and incomplete, so they are
not a native backend yet.

The planned native components cover the lower layers:

- pack storage;
- pack parsing and building;
- pack index and object lookup;
- delta reconstruction;
- object model and path resolution;
- ref storage and atomic updates;
- protocol client primitives;
- single-file remote fetch and push.

What is still missing is the repository-level component that wires those pieces
together behind `GitRepository` and `GitRepositoryProvider`.

## Non-Goals

Do not implement every Git protocol feature in the first repository backend step.

Do not remove `JGitRepository` immediately. Keep it as the compatibility backend
until native behavior reaches parity for selected workflows.

Do not make S3 the first required backend. Start with a local native backend that
is easier to test, then add S3 once storage contracts are stable.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code for the native backend. Tests may compare
native behavior against JGit or Git CLI fixtures.

Do not expose lower-level pack or ref storage details through `GitRepository`
unless a caller has an explicit need and the abstraction is stable.

## Backend Shape

Introduce a native repository composition:

```text
NativeGitRepository
  GitObjectStore
  GitPackStore
  GitPackIndexStore
  GitRefStore
  GitCommitProjectionStore
  GitRepositoryLocks
```

The repository should expose the existing `GitRepository` interface first:

- `name`;
- `description`;
- `withFetchAccessCheck`;
- `upload`;
- `receive`;
- `loadFiles`;
- `saveFiles`;
- `close`.

Where native protocol support is not implemented yet, methods should return
clear typed `GitOperationException` failures rather than falling back to JGit in
native-only mode.

## Repository Provider

Add a native provider behind configuration:

```text
git.storage.backend = jgit-file | native-file | native-s3
```

Provider responsibilities:

- validate repository names;
- resolve storage location;
- create repository metadata;
- initialize `HEAD`;
- open existing repositories;
- cache repository handles safely;
- close cached handles during shutdown;
- report unsupported backend capabilities clearly.

The first native provider can be local-file based. The S3 provider can use the
same logical repository backend once pack, index, and ref stores have S3
implementations.

## Repository Metadata

Add backend-neutral metadata:

- repository id or normalized name;
- storage format version;
- default branch;
- hash algorithm;
- object storage kind;
- ref storage kind;
- created time;
- last maintenance time;
- feature flags or capabilities.

Metadata should be written atomically during repository creation. A failed create
must not leave a repository that appears valid to `find()`.

## Local Native Layout

Use a local layout that is explicit and easy to inspect:

```text
<repo>/
  orion-repository.json
  refs/
  packs/
  indexes/
  projections/
  tmp/
  locks/
```

This does not need to mimic `.git` exactly. The native backend should use Orion's
own storage contracts. Compatibility with Git clients comes through protocol
serving, not by making the storage directory look like a Git repository.

All writes should stage under `tmp/`, validate, then publish atomically where the
local filesystem permits it.

## Capability Model

Expose repository backend capabilities:

- can load files;
- can save files;
- can serve upload-pack;
- can receive-pack;
- can list refs;
- can update refs atomically;
- can store packs;
- can rebuild projections;
- supports S3-compatible storage;
- supports multi-ref transactions.

Capability checks should let callers decide whether to use the native backend,
JGit compatibility backend, or a read-only mode.

## File Read Path

Implement `loadFiles` as the first useful native repository operation:

1. Normalize branch or ref to a full ref name.
2. Read the ref snapshot.
3. Resolve the commit object.
4. Parse the commit and root tree.
5. Resolve each requested path through the native tree resolver.
6. Load blob bytes with size limits.
7. Return `GitRepositoryFileSnapshot` with file bytes and commit id.

This read path should not require the commit information projection. It can use
the projection later as an optimization.

## File Save Path

Implement `saveFiles` after object builders, pack building, object storage, and
ref storage are available:

1. Read current branch ref.
2. Load base commit and root tree if the branch exists.
3. Normalize and validate every path.
4. Build new blob objects.
5. Rewrite affected trees.
6. Build a commit object.
7. Build a pack containing new objects.
8. Store and index the pack.
9. Verify new objects are readable.
10. Update the branch ref with expected old id.
11. Publish a ref update event.

If the ref update fails because another writer moved the branch, the stored pack
can remain as unreachable objects for later maintenance; the branch must remain
unchanged.

## Upload-Pack Serving

Native `upload` should eventually replace JGit `UploadPack` for the native
backend.

Repository responsibilities:

- advertise refs from `GitRefStore`;
- resolve wanted object ids;
- perform fetch access checks;
- enumerate reachable objects;
- build a pack from selected objects;
- write protocol responses through the native protocol serving layer.

This should depend on a separate native upload-pack server plan or component. The
repository backend should provide object and ref services, not mix protocol state
machines into storage classes.

## Receive-Pack Ingestion

Native `receive` should eventually replace JGit `ReceivePack` for the native
backend.

Repository responsibilities:

- parse incoming commands through the native protocol serving layer;
- parse and validate incoming pack bytes;
- reconstruct delta objects;
- build or verify pack indexes;
- ensure all command target objects exist;
- enforce update policies and fast-forward rules;
- update refs atomically;
- publish receive events.

Objects must be durable and readable before refs are updated.

## Projection Integration

The commit information model should be optional for correctness and useful for
speed.

The backend should support:

- synchronous projection updates for small internal writes when cheap;
- asynchronous projection rebuild after large receive-pack ingestion;
- marking projection status as stale while refs and objects remain canonical;
- rebuilding projections from refs and pack storage.

Callers should be able to read files through canonical objects even if projection
data is missing or stale.

## Migration Strategy

Run native and JGit-backed repositories side by side:

- keep existing `jgit-file` as default;
- add `native-file` as opt-in;
- add parity tests using the same fixtures against both backends;
- add import or migration tooling only after native storage stabilizes;
- do not silently open native storage as JGit or JGit storage as native.

Repository format versioning should allow future migrations without guessing
based on directory contents.

## S3 Strategy

After local native storage works, implement `native-s3` by swapping storage
adapters:

- S3 pack store;
- S3 pack index store;
- S3 ref store with conditional writes;
- S3 projection store;
- local temporary files or multipart staging for large pack operations.

The native repository backend should not know S3-specific API details. It should
depend on storage interfaces with clear consistency and atomicity contracts.

## Phased Plan

Phase 1: Backend interfaces and metadata.

Define native repository component interfaces, metadata format, capability model,
and dependency boundary tests proving no JGit dependency.

Phase 2: Native local repository provider.

Create and open local native repositories with metadata, default branch, `HEAD`,
and empty storage directories.

Phase 3: Native `loadFiles`.

Implement file reads through refs, object lookup, commit/tree parsers, path
resolver, and blob loader. Compare results with JGit for fixtures.

Phase 4: Native `saveFiles`.

Implement internal file writes through object builders, tree rewriter, pack
builder, pack storage, pack index, object lookup, and atomic ref update.

Phase 5: Repository events.

Publish ref update events for native saves with the same event shape as
JGit-backed receive events where applicable.

Phase 6: Native receive-pack integration.

Wire incoming pack ingestion and ref updates behind `GitRepository.receive` for
controlled protocol fixtures.

Phase 7: Native upload-pack integration.

Wire object enumeration and pack building behind `GitRepository.upload` for
controlled protocol fixtures.

Phase 8: Projection integration.

Update or rebuild commit information projections after native saves and receives.

Phase 9: S3 native backend.

Use S3 pack, index, ref, and projection stores under the same repository
abstraction.

Phase 10: Migration and parity.

Add repository format migration tooling and broaden parity tests until selected
workflows can default to native storage.

## Open Questions

Should `GitRepository` grow explicit capability methods, or should capabilities
remain on provider/backend-specific services?

Should native local storage intentionally avoid `.git` layout, or should it keep
a Git-compatible loose layout for easier debugging?

Should native `upload` and `receive` live directly on `NativeGitRepository`, or
should `GitRepository` delegate to separate protocol service classes?

How should unreachable packs created by failed ref updates be retained and
garbage-collected?

Should `saveFiles` support delete operations in the existing interface, or does
the interface need a richer change model before native save becomes complete?

How much projection freshness should `loadFiles` require when both canonical
objects and projection records are available?

## Verification

Cover at least these cases:

- native repository provider creates metadata, storage directories, and symbolic
  `HEAD`;
- invalid repository names are rejected without creating storage;
- existing native repository opens after process restart;
- native backend production code has no JGit dependency;
- `loadFiles` reads one file and multiple files from a branch;
- `loadFiles` reports missing branch, missing path, directory path, and oversized
  blob with typed errors;
- `saveFiles` creates a root commit on an unborn branch when allowed;
- `saveFiles` updates an existing branch with expected old id;
- `saveFiles` preserves unrelated tree entries;
- concurrent `saveFiles` calls allow only one matching ref update;
- failed ref update does not make the branch point to new objects;
- saved files can be read back through native `loadFiles`;
- JGit-backed and native-backed fixtures produce matching file snapshots and
  commit metadata for deterministic inputs;
- native receive-pack stores incoming packs and updates refs only after object
  validation;
- native upload-pack can serve a simple clone/fetch fixture;
- projection rebuild can recover from canonical objects and refs;
- S3 backend reports unsupported until its required storage adapters are present;
- native and JGit providers can coexist behind configuration.

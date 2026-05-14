# Native Git LoadFiles Read Path

## Goal

Add a JGit-free implementation plan for `GitRepository.loadFiles(...)` in native
repositories.

This read path is Orion's internal "read a small set of files from a Git branch"
operation. It is used by versioned storage and configuration-style workflows that
need file bytes plus a commit version without cloning a repository, checking out
a working tree, or using JGit.

The native implementation should compose existing lower-level plans:

- ref storage and ref snapshots;
- native object lookup;
- commit/tree/blob parsing from the native object model;
- tree path resolution;
- optional commit projection acceleration;
- repository capability reporting;
- typed errors and size limits.

The first version should walk canonical objects directly. Projection-backed reads
can become an optimization after the canonical path is correct.

## Current State

`GitRepository` exposes:

```text
loadFiles(String branch, List<String> paths) -> GitRepositoryFileSnapshot
```

The default interface implementation reports that file loading is unsupported.

`JGitRepository.loadFiles(...)` currently:

1. resolves the branch input;
2. fails with `GitRepositoryFileNotFoundException` when the branch is missing;
3. parses the branch target as a commit;
4. normalizes each requested path;
5. uses `TreeWalk.forPath(...)` from the commit tree;
6. fails with `GitRepositoryFileNotFoundException` when a path is missing;
7. opens each blob through JGit and reads bytes into memory;
8. returns a `GitRepositoryFileSnapshot` containing normalized path keys and the
   commit id as `version`.

The native repository backend plan names `loadFiles` as the first useful native
repository operation, but only lists the high-level steps.

The native object model plan defines path normalization, tree parsers, and tree
path resolution.

The commit projection plan defines projection-backed path metadata, but explicitly
keeps canonical object reads as the source of truth.

What is missing is a detailed operation-level plan for native `loadFiles`
semantics, consistency, errors, limits, projection fallback, and backend
integration.

## Non-Goals

Do not implement `saveFiles`, upload-pack, receive-pack, remote fetch, or working
tree checkout in this plan.

Do not require the commit projection for correctness.

Do not implement Git attributes, filters, CRLF conversion, LFS smudging, or
submodule checkout. Blob bytes are canonical Git blob bytes.

Do not stream arbitrary large files through the current public API. The existing
return type is `Map<String, byte[]>`, so enforce configured byte limits.

Do not expose hidden refs or internal refs unless repository policy allows this
internal read path to access them.

Do not depend on JGit in production code. Tests may compare native results
against JGit-backed fixtures.

## Public Semantics

The first native implementation should match current useful behavior:

- branch input can be a short branch name or full ref;
- short branch names resolve under `refs/heads/`;
- if full ref lookup fails and current compatibility requires it, short fallback
  behavior should be tested explicitly;
- missing branch fails as not found;
- target object must be a commit;
- each requested path is normalized through the Git path normalizer;
- returned map keys use normalized Git paths with `/`;
- missing path fails as not found;
- directory path fails as not found or typed directory error mapped to not found
  where existing callers expect it;
- regular file blob bytes are returned exactly;
- executable file blob bytes are returned exactly;
- symlink behavior is explicit by policy;
- result version is the resolved commit id;
- duplicate normalized requested paths are handled deterministically.

The operation should read from a stable ref/object snapshot. It must not combine
a ref target from one point in time with object directory state from another in a
way that returns a self-inconsistent snapshot.

## API Boundary

Keep `GitRepository.loadFiles(...)` as the compatibility API, but implement the
native path through internal request/result objects:

- `GitLoadFilesRequest`;
- `GitLoadFilePath`;
- `GitLoadFilesPolicy`;
- `GitLoadFilesResult`;
- `GitLoadFilesFailure`;
- `GitLoadFilesDiagnostics`;
- `GitLoadedFile`;

`GitLoadFilesRequest` should contain:

- repository id/name;
- branch or ref input;
- requested paths;
- maximum file count;
- maximum single blob bytes;
- maximum total bytes;
- projection policy;
- ref visibility policy;
- operation id for diagnostics.

`GitRepository.loadFiles(...)` can adapt its current parameters into this
request and return `GitRepositoryFileSnapshot`.

## Ref Resolution

Branch/ref resolution should mirror native `saveFiles` where possible.

Rules:

- full refs beginning with `refs/` are validated and resolved directly;
- short branch names become `refs/heads/<branch>`;
- invalid ref names fail before object lookup;
- missing refs fail with not found;
- symbolic `HEAD` can be supported only when explicitly requested by the input
  rules;
- hidden/internal refs are rejected unless the policy allows internal reads.

The read path should capture a ref snapshot:

- selected ref name;
- resolved object id;
- ref store version or etag when available;
- symbolic resolution chain when applicable.

The returned snapshot version should be the final commit id, not the ref storage
version.

## Object Directory Snapshot

Open an object directory snapshot that is compatible with the ref snapshot.

For local storage:

- list published pack manifests once;
- use that manifest set for the read;
- ignore packs published after the snapshot unless the read retries explicitly.

For S3 storage:

- use final manifests only;
- verify manifest references and checksums where the object directory requires
  it;
- treat missing pack/index referenced by a manifest as a typed storage
  inconsistency.

If the ref points to an object that is not visible in the object directory
snapshot, fail with a typed missing-object or storage-inconsistent error. Do not
silently read staged or quarantined objects.

## Path Validation

Use the shared Git path normalizer.

Validation:

- reject `null` paths;
- reject empty or blank paths;
- reject absolute paths;
- reject `..` traversal;
- normalize platform separators to `/`;
- collapse `.` segments;
- reject NUL bytes;
- reject empty path segments;
- reject trailing slash for file reads;
- preserve case exactly;
- detect duplicate normalized paths.

Duplicate behavior should be explicit. The native path can:

- reject duplicates as invalid input; or
- return one map entry for the normalized path.

Rejecting duplicates is preferable because it avoids silent changes in result map
size. If existing callers depend on deduplication, add compatibility tests.

## Commit Loading

After ref resolution:

1. load the target object header;
2. verify object type is commit;
3. load inflated commit content within configured metadata limits;
4. parse the commit with native commit parser;
5. extract root tree id and parent ids;
6. record commit id as the result version.

Failures:

- ref target missing;
- ref target not a commit;
- commit content corrupt;
- commit metadata limit exceeded;
- object storage read failure.

Annotated tags should not be accepted for branch reads unless a future API
allows tag dereferencing. If full ref input points at a tag, policy must decide
whether to peel or reject.

## Tree Path Resolution

Use the native tree resolver for each normalized path.

Resolver inputs:

- root tree id from the commit;
- normalized path;
- object loader capable of loading tree objects from the object directory
  snapshot;
- traversal limits.

Resolution should distinguish:

- regular file blob;
- executable file blob;
- symlink blob;
- directory;
- gitlink/submodule;
- missing path;
- wrong object type;
- corrupt tree;
- traversal limit exceeded.

The public compatibility API can map some of these to
`GitRepositoryFileNotFoundException`, but internal diagnostics should keep the
specific reason.

The resolver should not load blob bytes until the path has been resolved to a
blob object id and mode.

## Blob Loading

For each resolved blob:

1. load object header;
2. verify object type is blob;
3. verify size is within single-file limit;
4. verify cumulative result size is within total limit;
5. load exact blob bytes;
6. put bytes into the result under normalized path.

Because the public API returns byte arrays, the native implementation must guard
against unbounded memory use.

Suggested policy:

- small configuration file default limit;
- repository-specific override;
- separate total bytes limit for multi-file reads;
- fail before loading a blob when size metadata is available and too large.

If size metadata is unavailable until inflation, enforce limits during streaming
into a bounded byte sink.

## Symlink, Gitlink, and Directory Policy

Current JGit behavior opens whatever object `TreeWalk` resolves. For native code,
make policy explicit.

Default recommendation:

- regular file: return bytes;
- executable file: return bytes;
- symlink: reject unless caller allows symlink blobs;
- directory: not found or typed directory error;
- gitlink/submodule: reject as unsupported;
- tree object where blob expected: typed wrong-type error.

ACL/config storage should probably allow only regular/executable file blobs.
Future callers can opt into symlink reads if they have a safe interpretation for
symlink target bytes.

## Result Shape

Return:

- normalized path to byte array map;
- version as resolved commit id;
- optional internal diagnostics with ref name, commit id, blob ids, and source
  snapshot ids.

The public `GitRepositoryFileSnapshot` has:

```text
Map<String, byte[]> files
Optional<String> version
```

Native implementation should set `version` to the commit id when the ref
resolved successfully. Empty `version` should be reserved for backends that truly
cannot provide one.

## Projection Acceleration

Projection can accelerate path metadata lookup, but canonical object reads remain
authoritative.

Projection-backed flow:

1. resolve ref through current ref store or projection if policy allows;
2. query projection for commit root tree and path metadata;
3. verify projection version is current enough for policy;
4. load blob bytes from canonical object storage;
5. optionally verify blob id/mode against tree resolver for sensitive paths;
6. fall back to canonical tree walk on projection miss or stale projection.

Projection should not return blob bytes. It should return ids and metadata that
help avoid walking unrelated trees.

Access-control-sensitive reads should not depend on stale projection data unless
canonical verification is performed.

## Consistency Policy

A single `loadFiles` call should produce a coherent snapshot.

Use one of these strategies:

- capture ref snapshot first, then object directory snapshot that includes the
  commit object;
- capture object directory snapshot first, then ref snapshot and refresh object
  snapshot if the commit is not visible;
- use backend-provided repository snapshot token when available.

The first implementation can use ref-first with validation:

1. read ref target;
2. open object directory snapshot;
3. verify commit exists in that snapshot;
4. read all requested paths from that snapshot.

If verification fails because publication is in progress, return a typed
inconsistent-storage error or retry once according to policy. Do not mix objects
from different snapshots inside one result.

## Error Model

Use typed internal failures:

- unsupported backend capability;
- invalid ref name;
- missing ref;
- hidden ref;
- invalid path;
- duplicate normalized path;
- requested file count limit exceeded;
- ref target missing;
- ref target type mismatch;
- corrupt commit;
- missing root tree;
- corrupt tree;
- missing path;
- directory path;
- symlink unsupported;
- gitlink unsupported;
- blob object missing;
- blob type mismatch;
- single blob size limit exceeded;
- total result size limit exceeded;
- object storage read failure;
- projection stale/unavailable where required;
- repository snapshot inconsistent.

Map compatibility cases to existing exceptions:

- missing ref/path to `GitRepositoryFileNotFoundException`;
- invalid input to `IllegalArgumentException` or a specific
  `GitOperationException`;
- storage/corruption/limit errors to `GitOperationException`.

## Versioned Storage Integration

`GitRepositoryProviderVersionedStorage` and `LocalGitVersionedStorage` translate
`GitRepositoryFileSnapshot` into `VersionedFileSnapshot`.

Native `loadFiles` should preserve:

- returned file keys are normalized Git paths;
- returned version is commit id;
- missing path maps to `Result.FailureCode.NOT_FOUND` through existing exception
  handling;
- no repository creation occurs for read-only `find` paths.

Future versioned save APIs can use this commit id as expected-old version for
stale update detection.

## Capability Model

Native repository capabilities should distinguish:

- can load files through canonical objects;
- can load files through projection acceleration;
- supports symlink file reads;
- supports executable mode metadata;
- supports expected-version reads if added later;
- supports S3 snapshot-consistent reads.

Callers should be able to choose JGit compatibility backend or native backend
based on these capabilities during rollout.

## Local Backend Flow

Local native `loadFiles` flow:

1. validate request and normalize paths;
2. resolve ref from local ref store;
3. open local object directory snapshot from final manifests;
4. load and parse commit;
5. resolve paths through pack-backed tree loader;
6. load bounded blob bytes;
7. return snapshot.

Local filesystem errors should include sanitized path context but not leak object
contents.

## S3 Backend Flow

S3 native `loadFiles` flow:

1. validate request and normalize paths;
2. resolve ref from S3 ref store with version/etag;
3. open S3 object directory snapshot from final manifests;
4. use pack indexes and range reads for tree/blob objects;
5. batch or cache nearby metadata reads where practical;
6. enforce timeout and byte limits;
7. return snapshot.

S3 backend should avoid one unbounded read per pack. It can still start simple
with correctness first, then add range-read batching and parsed tree caching.

## Caching

Safe caches:

- parsed commit headers by object id;
- parsed tree objects by tree id;
- pack index fanout tables;
- small blob bytes under configured limit;
- projection query results tied to projection version.

Caches must be invalidated or versioned by object id and snapshot identity where
needed. Since Git objects are immutable by id, object-content caches are safe as
long as storage corruption checks are handled.

Ref resolution results should not be cached across calls unless tied to ref store
version and policy.

## Observability

Record diagnostics:

- repository id;
- normalized ref name;
- commit id;
- path count;
- bytes returned;
- projection hit/miss/stale;
- tree objects loaded;
- blob objects loaded;
- object directory snapshot id;
- duration;
- failure category.

Do not log blob contents. Hidden ref names should be omitted from user-visible
errors.

## Implementation Phases

Phase 1: Request/result model.

Add native load request, policy, result, loaded-file, diagnostics, and typed
failure models. Keep the public `loadFiles(...)` adapter unchanged.

Phase 2: Ref and path preparation.

Normalize branch/ref input, validate paths, detect duplicates, enforce file count
limits, and capture a ref snapshot.

Phase 3: Canonical commit loading.

Open object directory snapshot, load target commit, parse root tree, and return
typed missing/corrupt/type errors.

Phase 4: Tree path resolution.

Resolve requested paths through native tree resolver using object directory
snapshot loaders. Cover regular files, missing paths, directories, symlinks, and
gitlinks.

Phase 5: Blob loading.

Load blob bytes with single-file and total-size limits. Return normalized path
keys and commit version.

Phase 6: Compatibility adapter.

Map native result/failures into `GitRepositoryFileSnapshot`,
`GitRepositoryFileNotFoundException`, and `GitOperationException` for the current
interface.

Phase 7: Local backend integration.

Expose native `loadFiles(...)` from `NativeGitRepository` over local ref/object
stores and compare behavior with JGit fixtures.

Phase 8: Projection acceleration.

Use commit projection for root tree and path metadata when current, with
canonical fallback on miss/stale according to policy.

Phase 9: S3 backend integration.

Use S3 ref store, pack indexes, range reads, and object directory snapshots to
provide equivalent behavior on `native-s3`.

Phase 10: Versioned storage rollout.

Allow versioned storage to use native `loadFiles` behind backend capability
checks. Preserve existing not-found and version behavior.

## Verification

Cover at least these cases:

- production native load path has no JGit dependency;
- short branch names resolve to `refs/heads/<branch>`;
- full ref names are preserved after validation;
- missing branch returns not found;
- ref target that is not a commit returns typed failure;
- absolute path, empty path, `..`, NUL path, and duplicate normalized paths are
  rejected;
- one regular file loads with exact bytes;
- multiple files load from the same commit and return one version;
- returned map keys are normalized Git paths;
- missing path returns not found;
- directory path does not return arbitrary bytes;
- executable file blob bytes load correctly;
- symlink and gitlink behavior follows policy;
- oversized blob fails before unbounded memory growth;
- total result size limit is enforced across multiple files;
- corrupt commit and corrupt tree produce typed failures;
- object directory snapshot remains stable while another pack is published;
- projection hit returns the same bytes and version as canonical tree walk;
- stale projection falls back to canonical walk when policy allows it;
- versioned storage maps missing file to `Result.FailureCode.NOT_FOUND`;
- saved files from native `saveFiles` can be read back through native
  `loadFiles`;
- local and S3-backed native repositories expose equivalent read behavior once
  both backends are implemented;
- JGit-backed and native-backed fixtures return matching file snapshots for
  deterministic repositories.

## Open Questions

Should duplicate normalized paths be rejected, or should native behavior collapse
them into one map entry for compatibility with `Map` results?

Should symlink blobs be readable by default, or should config/ACL storage reject
them unless explicitly allowed?

Should full refs outside `refs/heads/` be accepted by `loadFiles(...)`, or should
the public API be branch-only despite accepting a string today?

Should missing directory path map to not found for compatibility, or should it
become a distinct `GitRepositoryFileIsDirectoryException`?

Should `loadFiles(...)` retry once when the ref exists but its target object is
not yet visible in the object directory snapshot?

Should a future API return file modes and blob ids alongside bytes so callers can
distinguish regular files, executable files, and symlinks?

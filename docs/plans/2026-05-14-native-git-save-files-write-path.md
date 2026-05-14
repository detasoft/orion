# Native Git SaveFiles Write Path

## Goal

Add a JGit-free implementation plan for `GitRepository.saveFiles(...)` in native
repositories.

This write path is Orion's internal "small file update" operation. It is used by
versioned storage and configuration-style workflows that need to update one or
more files on a branch without checking out a working tree and without running a
full Git receive-pack session.

The native implementation should compose existing lower-level plans:

- path normalization and tree rewriting from the native object model;
- canonical blob/tree/commit builders;
- deterministic pack building;
- pack publication and object lookup;
- atomic ref updates;
- projection updates;
- repository events and diagnostics.

It should preserve the useful behavior of the current JGit-backed
`JGitRepository.saveFiles(...)` while making conflict handling, durability, and
rollback behavior explicit.

## Current State

`GitRepository` exposes:

```text
saveFiles(String branch, Map<String, byte[]> files, String message, GitCommitAuthor author)
```

The default interface implementation reports that file saving is unsupported.

`JGitRepository.saveFiles(...)` currently:

1. resolves the branch;
2. reads every recursive tree entry from the parent commit when the branch
   exists;
3. inserts every requested file as a regular blob;
4. replaces or adds those file paths in an in-memory tree map;
5. writes a new tree;
6. writes a commit with the previous commit as parent when one exists;
7. flushes the object inserter;
8. updates the branch with expected old id.

Current semantics are intentionally simple:

- branch names are normalized to `refs/heads/<branch>` unless already full refs;
- missing branch creates a root commit;
- empty or blank commit message becomes `"update files"`;
- author and committer are the same identity;
- all saved files are regular files;
- requested paths replace existing entries;
- file deletes are not supported by the existing public method;
- concurrent branch movement fails through expected-old ref update.

The native repository backend plan includes a short file save path. The object
model, pack builder, object publication, ref storage, and projection plans define
the pieces. What is missing is a detailed operation-level plan for preserving
`saveFiles` behavior while coordinating those pieces safely.

## Non-Goals

Do not implement receive-pack or remote push in this plan.

Do not implement a working tree, index file, merge engine, rebase, checkout, or
Git attributes.

Do not add delete support to the current public `saveFiles` method unless the
interface is explicitly extended. A richer change API can support deletes later.

Do not implement conflict auto-merge. A stale ref update should fail clearly and
let the caller reload and retry.

Do not make projection updates part of the commit/ref atomicity boundary.
Canonical objects and refs remain valid if projection update fails.

Do not depend on JGit in production code. Tests may compare native output against
JGit-backed fixtures.

## Public Semantics

The first native implementation should match the current public method:

- input branch can be short branch name or full ref;
- short branch names resolve under `refs/heads/`;
- branch creation is allowed when repository policy allows internal writes;
- each map entry writes one regular file blob;
- duplicate normalized paths are rejected deterministically;
- a file path cannot be absolute, blank, NUL-containing, or escape with `..`;
- message fallback is `"update files"`;
- author fallback is `GitCommitAuthor.EMPTY`;
- author and committer are identical unless a later API separates them;
- a successful call creates exactly one commit and one ref update;
- an empty file map is rejected or treated as no-op by explicit policy, not by
  accident.

The operation should be all-or-nothing at the ref level. It may publish a valid
pack that becomes unreachable if the final ref compare-and-set fails, but the
branch must not move unless the expected old id still matches.

## API Boundary

Keep `GitRepository.saveFiles(...)` as the compatibility API, but implement the
native path through explicit internal request/result objects:

- `GitSaveFilesRequest`;
- `GitSaveFileChange`;
- `GitSaveFilesPolicy`;
- `GitSaveFilesResult`;
- `GitSaveFilesFailure`;
- `GitSaveFilesConflict`;
- `GitSaveFilesOperation`;
- `GitSaveFilesDiagnostics`.

`GitSaveFilesRequest` should contain:

- repository id/name;
- target ref name;
- normalized branch input;
- files to write;
- commit message;
- author;
- clock or commit timestamp source;
- expected old id when a future versioned API supplies it;
- operation id for diagnostics and projection/event correlation.

`GitRepository.saveFiles(...)` can adapt its current parameters into this
request.

## Ref Resolution

Branch/ref resolution should be explicit.

Rules:

- full refs beginning with `refs/` are used as provided after validation;
- short branch names become `refs/heads/<branch>`;
- invalid ref names fail before object building;
- hidden/internal refs are rejected unless internal policy allows them;
- current ref snapshot is captured before object creation;
- absent branch is represented as expected old zero id.

For a missing branch, the write creates a root commit. For an existing branch,
the write creates a child commit with the old commit as its only parent.

The final ref update must use compare-and-set:

- expected old zero id for branch creation;
- expected old commit id for update;
- no silent overwrite when another writer moved the ref.

## Path Validation

Use the native Git path normalizer from the object model plan.

Validation:

- reject `null` paths;
- reject empty or blank paths;
- reject absolute paths;
- reject `..` traversal;
- normalize platform separators to `/`;
- collapse `.` segments;
- reject NUL bytes;
- reject empty path segments;
- reject trailing slash for file writes;
- reject path that resolves to repository root;
- preserve case exactly;
- detect duplicate normalized paths.

The native behavior should be at least as strict as the current JGit path helper.
Stricter rejection is acceptable when it prevents ambiguous writes.

## File Content Policy

The current API accepts `Map<String, byte[]>`, so the first native
implementation can keep in-memory bytes.

Policy should still define:

- maximum number of files;
- maximum single file size;
- maximum total payload size;
- whether empty files are allowed;
- default file mode: regular file;
- whether executable or symlink modes require a future richer API;
- whether unchanged content creates a no-op or a new commit.

Start with regular-file writes only. Existing executable bits can be preserved
only for unchanged paths; rewritten paths become regular files unless the API is
extended.

## Base Tree Loading

For existing branches:

1. load the old commit object through the native object store;
2. verify the target object is a commit;
3. read the root tree id;
4. load only trees needed by changed paths when possible;
5. verify changed paths do not conflict with directories unless replacement
   policy explicitly allows it.

For an unborn branch:

- use an empty tree as the base;
- create tree objects for all path prefixes required by the files.

The first implementation may materialize the full tree for parity with the JGit
path. A follow-up optimization should load only changed subtrees through the tree
rewriter.

## Tree Rewrite

Use the native tree rewriter.

For each saved file:

1. build a blob object from exact bytes;
2. create or update the leaf entry with regular-file mode;
3. rebuild affected parent trees bottom-up;
4. preserve unchanged tree entries and object ids;
5. return the new root tree id and all new tree objects.

Conflict cases:

- writing `a/b` when `a` is an existing regular file fails unless replacement
  policy is added;
- writing `a` when `a/b` exists fails unless directory replacement policy is
  added;
- duplicate normalized path fails;
- invalid existing tree data fails with typed corruption.

If the new root tree id equals the old root tree id, policy decides whether to:

- create an empty/no-op commit;
- return a no-op result without moving the ref;
- reject as no changes.

The first implementation should choose one policy and test it. Matching current
JGit behavior may create a new commit because files are reinserted and commit
metadata changes even when content is identical.

## Commit Creation

Build a canonical commit object with:

- new root tree id;
- parent id only when the branch previously existed;
- author identity;
- committer identity;
- timestamp source;
- UTF-8 encoding;
- normalized commit message;
- optional extra headers only after the API supports them.

For deterministic tests, inject a fixed clock and identity.

The commit builder should return:

- canonical commit bytes;
- commit id;
- parsed metadata summary for events/projection;
- object write entry for pack building.

## Object Set and Pack Building

The operation should build a pack containing only new objects:

- new blobs for changed files;
- new trees from the rewritten path prefixes;
- new commit object.

It should not include unchanged blobs or unchanged trees that already exist in
the base repository.

The first native implementation can use no-delta pack building. Delta selection
is unnecessary for small internal config writes and can be added later through
the pack builder's delta policy.

Pack building should be deterministic in tests:

- fixed object order;
- fixed compression settings;
- fixed clock/identity/message.

## Pack Publication

Use the object store publication service.

Flow:

1. start a pack publication transaction with source `save-files`;
2. stream the generated pack into staged storage;
3. parse/validate the pack;
4. build or attach the pack index;
5. publish pack, index, and manifest;
6. open an object directory snapshot that includes the new pack;
7. verify the new commit id is readable;
8. mark the pack as referenced after ref update succeeds.

If publication fails, the ref must not change.

If publication succeeds but ref update fails, keep the pack valid but orphaned
for maintenance according to object store policy.

## Ref Update

After object publication and readability verification, update the target ref via
`GitRefStore`.

Rules:

- create absent branch only when expected old is zero/absent;
- update existing branch only when expected old id matches captured snapshot;
- reject stale ref without retry by default;
- update `HEAD` only when repository creation/default branch policy requires it;
- emit a clear conflict result when another writer moved the branch.

The update should be fast-forward by construction because the new commit parent
is the captured old commit. If the base ref changes before CAS, do not rebase or
merge automatically.

## Events

Publish a repository ref update event after the ref update result is known.

Event fields should match existing receive/update event expectations where
possible:

- repository name;
- ref name;
- old id;
- new id;
- update type: create or update;
- user/author identity where available;
- result;
- source: save-files.

Do not publish a successful ref update event when pack publication succeeds but
ref CAS fails.

## Projection Integration

After success:

- emit pack publication metadata;
- emit ref update metadata;
- update commit projection synchronously for small writes when cheap;
- otherwise mark projection stale and schedule incremental update.

Projection failure must not roll back the ref update. It should be reported as
diagnostics and handled by maintenance/rebuild.

## Versioned Storage Integration

`GitRepositoryProviderVersionedStorage` and `LocalGitVersionedStorage` currently
call `saveFiles(...)` without an expected old version parameter.

The native implementation should preserve that behavior first.

A future versioned save API should add expected snapshot/version support:

- load returns commit id;
- save can require expected old commit id;
- stale version produces a typed conflict instead of a generic runtime failure;
- callers can choose reload-and-retry policy.

This should be a follow-up API evolution. The first native backend can still
protect against concurrent writes using the ref id captured at operation start.

## Error Model

Use typed failures:

- unsupported backend capability;
- invalid ref name;
- invalid path;
- duplicate normalized path;
- file count limit exceeded;
- file size limit exceeded;
- missing base commit;
- base object type mismatch;
- corrupt commit or tree;
- path conflict with directory/file;
- blob build failure;
- tree rewrite failure;
- commit build failure;
- pack build failure;
- pack publication failure;
- new commit not readable after publication;
- stale ref conflict;
- ref update failure;
- projection update failure.

Transport/protocol error models are not involved. This is a repository API
operation, so callers should receive `GitOperationException` or a richer native
result mapped to it.

## Concurrency

The operation should avoid holding a repository-wide lock while building objects
or writing pack bytes.

Safe sequence:

1. capture current ref snapshot;
2. build new objects from that snapshot;
3. publish pack;
4. compare-and-set ref;
5. mark pack referenced or orphaned.

Concurrent writers may publish packs concurrently. Only one writer can win the
final ref compare-and-set for the same branch snapshot.

The losing writer leaves a valid orphaned pack and receives a stale-ref conflict.

## Idempotency

The public method is not fully idempotent because a repeated call can create a
new commit with a new timestamp.

Internal operation ids can still make side effects easier to diagnose:

- pack publication source id;
- ref update attempt id;
- projection event id;
- maintenance orphan correlation.

Do not deduplicate successful commits by request id unless a future API requires
idempotent client retries.

## Local and S3 Backends

Local native backend:

- use local object store publication;
- use local ref store compare-and-set;
- use local projection store where enabled.

S3 native backend:

- use staged S3 pack publication;
- use conditional ref writes;
- publish final manifest before ref update;
- handle S3 retry/uncertain publication states before attempting ref CAS.

Both backends should expose the same `saveFiles` behavior through
`GitRepository`.

## Implementation Phases

Phase 1: Request/result model.

Add native save request, change, policy, result, diagnostics, and typed failure
models. Keep the public `saveFiles(...)` adapter unchanged.

Phase 2: Ref and path preparation.

Normalize branch/ref, capture current ref snapshot, validate paths, detect
duplicates, enforce file count and byte limits.

Phase 3: Base loading.

Load existing commit/root tree or create an empty-tree base for unborn branches.
Return typed errors for missing or corrupt base state.

Phase 4: Blob and tree building.

Build regular-file blobs and rewrite the base tree through the native object
model. Cover nested paths and path conflict behavior.

Phase 5: Commit building.

Build deterministic canonical commit objects with injected clock/identity in
tests and current JGit-compatible message fallback.

Phase 6: Pack building.

Build a no-delta pack containing only new blobs, trees, and commit objects.
Parse the generated pack back through native parser in tests.

Phase 7: Pack publication.

Publish pack/index/manifest through the object store publication service and
verify the new commit is readable before ref updates.

Phase 8: Atomic ref update.

Update the target ref with expected old id. Report stale conflicts and mark
unreferenced packs orphaned when CAS fails.

Phase 9: Events and projection.

Publish ref update events and feed projection update hooks. Handle projection
failures as stale projection diagnostics.

Phase 10: Backend integration.

Expose native `saveFiles(...)` from `NativeGitRepository` for local backend, then
S3 backend once storage adapters support the required operations.

Phase 11: Versioned API follow-up.

Add optional expected-version save API for callers that need explicit stale
snapshot handling.

## Verification

Cover at least these cases:

- production native save path has no JGit dependency;
- short branch names resolve to `refs/heads/<branch>`;
- full ref names are preserved after validation;
- invalid branch/ref name is rejected before object creation;
- absolute path, empty path, `..`, NUL path, and duplicate normalized paths are
  rejected;
- saving one file on an unborn branch creates a root commit and branch ref;
- saving one file on an existing branch creates a child commit with old commit as
  parent;
- saving multiple files creates one commit and one ref update;
- nested file save creates only required parent tree objects;
- updating one file preserves unrelated tree entries;
- same path update replaces blob content;
- empty file content is saved correctly;
- blank commit message becomes `"update files"`;
- author fallback matches current behavior;
- generated blob/tree/commit ids match Git CLI or JGit fixtures for deterministic
  inputs;
- generated pack is parseable by native parser and Git/JGit fixture readers;
- ref is not updated if pack publication fails;
- ref is not updated if new commit cannot be read after publication;
- stale ref compare-and-set fails without moving the branch;
- stale ref after pack publication leaves a valid orphaned pack for maintenance;
- successful save emits one ref update event;
- projection update receives commit/tree/ref metadata after success;
- projection failure does not roll back the saved commit;
- local and S3-backed native repositories expose equivalent save behavior once
  both backends are implemented;
- versioned storage can save through native `GitRepository.saveFiles(...)`.

## Open Questions

Should `saveFiles(...)` reject empty file maps, return no-op success, or create a
metadata-only commit?

Should unchanged file content create a new commit for parity with current JGit
behavior, or return no-op to reduce repository churn?

Should the public API grow delete, executable mode, symlink mode, and expected
old version support before native save becomes the default?

Should `saveFiles(...)` ever update non-branch refs, or should full refs outside
`refs/heads/` be rejected for internal writes?

Should `HEAD` be initialized or moved by `saveFiles(...)` when the first write
creates a non-default branch?

How should callers receive stale-ref conflicts: typed result, specific
`GitOperationException` subtype, or integration through the existing
`VersionedStorage` result model?

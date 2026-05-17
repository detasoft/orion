# Native Remote Git Batch File Fetch

## Goal

Add a JGit-free way to read multiple files from the same remote Git repository
and ref through the Git protocol while sharing ref discovery, commit fetch, tree
resolution, and blob fetch work across all requested paths.

The first production use case is remote ACL bootstrap storage, where several
configured files can live in the same repository and ref. Orion should avoid
cloning a worktree and avoid running one complete remote fetch flow per file.

## Current State

- `2026-05-14-native-remote-git-single-file-fetch.md` defines the first
  JGit-free `loadFile` path and calls out a batch follow-up.
- `2026-05-14-native-git-protocol-client-primitives.md` defines shared
  upload-pack client primitives that can request and parse pack streams.
- `2026-05-14-native-git-load-files-read-path.md` covers local native
  `GitRepository.loadFiles(...)`, but not remote Git protocol reads.
- Remote ACL storage currently needs multiple file reads from the same remote
  and should be able to use one coherent remote snapshot.

## Non-Goals

- Do not implement remote Git writes or push.
- Do not clone a full worktree.
- Do not shell out to the `git` executable in production code.
- Do not depend on JGit in production code.
- Do not fetch Git LFS object contents. Return LFS pointer bytes as normal blob
  content.
- Do not introduce persistent repository storage for transient remote objects in
  this step.

## Public API

Add a batch request next to the single-file API:

```text
NativeRemoteGitFileClient.loadFiles(request) -> RemoteGitFileSnapshotSet
```

Request fields:

- remote URI;
- target ref, branch, tag, or explicit commit id;
- repository path when the URI scheme requires it;
- ordered list of file paths inside the Git tree;
- optional authentication reference;
- connect, read, and total operation timeouts;
- maximum transferred pack bytes;
- maximum returned bytes per file;
- maximum total returned bytes;
- strict minimal-transfer flag;
- partial result policy;
- allowed protocol versions and transports.

Snapshot set fields:

- resolved commit id;
- ref name or explicit commit input that produced the snapshot;
- per-path result in caller order;
- blob object ids and Git file modes for found files;
- file bytes for found files;
- typed per-path failures for missing paths, directories, submodules, symlinks
  when disallowed, oversized files, and unsupported file types;
- transfer metadata shared by all paths;
- whether partial clone filtering or compatibility fallback was used.

## Result Semantics

The batch operation should return a coherent snapshot from one resolved commit.
It must not resolve the same branch separately for each path.

Support two partial result policies:

- `REQUIRE_ALL`: any missing or invalid path fails the entire operation after
  diagnostics are collected.
- `ALLOW_PARTIAL`: return found files and per-path failures together.

Duplicate paths should be normalized once and returned in the caller's order.
Conflicting normalized paths should be rejected before network I/O.

## Efficient Batch Algorithm

Preferred protocol v2 flow when the remote supports filtering:

1. Resolve the requested ref with `ls-refs`, or validate the explicit commit id
   policy.
2. Fetch the commit and required tree objects with `filter blob:none`.
3. Parse returned commit and tree objects into an in-memory object view.
4. Resolve all requested paths against the same tree root.
5. Collect the distinct blob ids needed for successful file paths.
6. Fetch all missing blob ids in one upload-pack request when the server accepts
   multiple wants.
7. Parse the blob pack and populate per-path results.
8. Enforce per-file and total returned-byte limits before exposing bytes.

If a server does not support the efficient flow, compatibility mode may fetch a
bounded shallow pack and extract requested blobs from it. Strict mode should fail
with a typed unsupported-capability error instead of silently downloading a large
pack.

## Path Resolution

Batch resolution should reuse tree objects across paths:

- normalize all paths before remote I/O;
- split paths into segments and build a prefix tree of requested paths;
- fetch or parse each tree object once;
- reuse intermediate tree entries for shared directories;
- preserve caller order in the final result;
- report typed failures per path without hiding failures for other paths.

Handle at least these file types:

- regular file blob;
- executable file blob;
- symlink blob when the request allows symlinks;
- directory requested as file;
- missing path;
- gitlink/submodule entry.

## Transient Object Handling

The batch client should keep fetched objects in a per-request transient object
view:

- object ids must be validated against Git object content;
- duplicate objects from multiple packs should be accepted if bytes match;
- mismatched duplicate object ids should fail the operation;
- large pack streams may spill to temporary files behind a bounded interface;
- temporary files must be deleted after the request completes or fails.

Do not publish transient remote objects into Orion repository storage as part of
this feature.

## Limits and Cancellation

Enforce:

- maximum number of requested paths;
- maximum normalized path length;
- maximum path depth;
- maximum transferred pack bytes;
- maximum parsed object count;
- maximum returned bytes per file;
- maximum total returned bytes;
- total request timeout;
- cancellation from the calling service.

Limit failures should identify whether the limit was per path, per pack, or per
request. They must not include file contents or credentials.

## ACL Integration

Remote ACL bootstrap storage should group configured ACL files by:

- remote URI;
- target ref;
- credential reference;
- strict/fallback policy.

Each group can call `loadFiles(...)` once. ACL-specific validation decides which
paths are required and which are optional. The Git client should stay generic and
return per-path results without knowing ACL semantics.

In native-only mode, ACL loading must not fall back to JGit when batch fetch
fails. It should return a clear typed failure that includes the remote capability
or path issue.

## Observability

Record safe diagnostics:

- remote host and sanitized repository identifier;
- transport type;
- protocol version;
- selected capabilities;
- number of requested paths;
- number of distinct tree objects parsed;
- number of distinct blobs fetched;
- transferred pack byte counts;
- fallback mode;
- elapsed time;
- failure category.

Never log credentials, authorization headers, full file contents, or raw remote
URLs containing embedded credentials.

## Implementation Plan

### Phase 1: Request and Result Model

- Add batch request, snapshot set, per-path result, and partial result policy
  types.
- Add dependency boundary tests proving production code does not depend on JGit.
- Add path normalization tests before any protocol work.

### Phase 2: Shared Ref Resolution

- Reuse the single-file ref discovery path.
- Resolve branch, tag, full ref, and explicit commit input once per request.
- Test annotated tag peeling and missing ref behavior.

### Phase 3: Commit and Tree Fetch

- Fetch commit and tree objects with `filter blob:none` where supported.
- Build a transient object view for returned commit and tree objects.
- Test filtered pack parsing and object id validation.

### Phase 4: Multi-Path Tree Resolver

- Build a requested-path prefix tree.
- Resolve shared directories once and return ordered per-path outcomes.
- Test shared prefixes, duplicate paths, missing paths, directories, symlinks,
  executables, and submodules.

### Phase 5: Batch Blob Fetch

- Collect distinct blob ids and fetch them in one upload-pack request.
- Map fetched blobs back to all requested paths that reference them.
- Enforce per-file and total returned-byte limits.
- Test duplicate blob reuse and oversized file behavior.

### Phase 6: Compatibility Fallback

- Add bounded shallow-pack fallback for remotes without required filtering.
- Keep strict mode as fail-fast.
- Test fallback extraction and strict unsupported-capability failures.

### Phase 7: Cancellation and Temporary Storage

- Add cancellation checks around network reads, pack parsing, and blob material
  extraction.
- Add temporary-file spill support for large packs if in-memory limits are not
  enough.
- Test cancellation cleanup and temporary file deletion.

### Phase 8: ACL Read Integration

- Group remote ACL file loads by remote/ref/credential.
- Replace repeated single-file calls with the batch API in native-only mode.
- Test required and optional ACL file behavior without JGit fallback.

### Phase 9: Observability and Redaction

- Add metrics and structured diagnostic events.
- Redact credentials and remote URLs.
- Test safe failure messages for authentication, capability, path, and size
  errors.

## Verification Plan

- Batch model serialization and validation tests.
- Dependency boundary test for no production JGit dependency.
- Path normalization and duplicate/conflict tests.
- Scripted upload-pack tests for filtered commit/tree fetch.
- Multi-path resolver tests for shared directory prefixes.
- Batch blob fetch tests with repeated blob ids and multiple wants.
- Strict mode test against a server without `filter`.
- Compatibility fallback test with a bounded shallow pack.
- Per-file and total byte limit tests.
- Cancellation cleanup tests.
- ACL integration tests proving one batch request loads multiple configured
  files from the same remote/ref.
- Redaction tests proving credentials and file contents are absent from logs and
  exceptions.

## Acceptance Criteria

- `loadFiles(...)` reads multiple files from one resolved remote commit without
  cloning a worktree.
- Shared commit and tree objects are fetched and parsed once per batch request.
- Distinct blobs are fetched together when the remote supports it.
- The result preserves caller order and reports typed per-path failures.
- Strict mode refuses inefficient remotes instead of silently downloading large
  packs.
- Compatibility mode can extract requested files from a bounded shallow pack.
- Remote ACL loading can use the batch API in native-only mode without JGit.
- Diagnostics are useful and redacted.

## Open Questions

- Should the first implementation support partial results, or should it start
  with `REQUIRE_ALL` only?
- What should the default maximum path count be for ACL bootstrap?
- Should explicit commit ids be allowed before hosted-provider compatibility is
  tested?
- Should temporary pack spill use the general storage abstraction or a dedicated
  request-local temp-file helper?
- Should symlinks be allowed by default for ACL files, or rejected unless the
  caller opts in?

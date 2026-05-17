# Native Remote Git Batch File Push

## Goal

Add a JGit-free way to update multiple files in the same remote Git repository
and ref through one Git receive-pack operation.

The first production use case is remote ACL bootstrap storage. When ACL state is
split across several configured files, Orion must save those files atomically in
one commit instead of issuing separate pushes that can leave the remote snapshot
partially updated.

## Current State

- `2026-05-14-native-remote-git-single-file-push.md` defines the first JGit-free
  remote `saveFile` path and calls out batch push as a follow-up.
- `2026-05-16-native-remote-git-batch-file-fetch.md` defines coherent remote
  multi-file reads from one resolved commit.
- `2026-05-14-native-git-save-files-write-path.md` defines local native
  multi-file tree rewriting and commit creation.
- `2026-05-14-git-pack-build-from-changes.md` defines pack creation for generated
  Git objects.
- Remote ACL save still needs one atomic native write for all configured ACL
  files without JGit and without a worktree.

## Non-Goals

- Do not implement arbitrary local repository push or history synchronization.
- Do not clone, checkout, merge, rebase, or use a working tree.
- Do not shell out to the `git` executable in production code.
- Do not depend on JGit in production code.
- Do not implement force-push by default.
- Do not implement Git LFS upload. If callers write LFS pointer bytes, push them
  as ordinary Git blobs.
- Do not add multi-ref transactions in this step. One request updates one ref.

## Public API

Add a batch write method next to the single-file API:

```text
NativeRemoteGitFileClient.saveFiles(request) -> RemoteGitFilePushResult
```

Request fields:

- remote URI;
- target branch or full ref;
- expected old commit id;
- ordered list of file changes;
- author and committer identity;
- commit message;
- optional authentication reference;
- connect, read, write, and total operation timeouts;
- maximum input bytes per file;
- maximum total input bytes;
- maximum outgoing pack bytes;
- stale-ref retry policy;
- branch creation policy;
- no-op policy;
- symlink and executable mode policy;
- force update flag, default false.

Each file change contains:

- normalized path inside the Git tree;
- operation: add/update, delete, or mode-only update;
- file bytes for add/update;
- requested Git file mode;
- optional expected previous blob id for stronger caller-side conflict checks.

Result fields:

- ref name;
- old commit id;
- new commit id;
- changed paths in caller order;
- new blob ids for written files;
- deleted path markers;
- pushed object ids;
- outgoing pack hash and size;
- remote receive status;
- whether a retry was used.

Use typed failures for invalid path, overlapping paths, empty change set,
missing ref, stale ref, missing base object, unsupported file type,
authentication failure, protocol error, pack build failure, remote reject,
configured size limit, and unsupported transport.

## Atomic Semantics

The batch operation must create one new root tree, one commit, one outgoing pack,
and one compare-and-set ref update.

All requested changes either become visible together at the new commit or none of
them become visible. If receive-pack rejects the ref update, the method reports a
typed failure and must not claim partial success.

The operation should preserve one resolved base commit for the whole request. It
must not refetch or resolve the branch independently for each path.

## Change Validation

Validate before network I/O when possible:

- paths are normalized and relative;
- no empty path is accepted;
- no path escapes through `.` or `..`;
- no two changes target the same normalized path;
- no path overlaps another path as file and descendant, such as `a` and `a/b`;
- file byte limits are enforced;
- delete changes do not carry file bytes;
- file modes are limited to supported regular file, executable, and optional
  symlink modes.

When the base tree is known, validate:

- deleting a missing path follows the configured missing-delete policy;
- directory-as-file conflicts fail unless a replacement policy explicitly allows
  them;
- submodule gitlinks are rejected;
- symlinks are rejected unless the request allows them;
- optional expected previous blob ids match the base tree.

## Tree Rewrite Algorithm

1. Resolve the base ref once.
2. Fetch the base commit and tree objects needed for all changed paths.
3. Build a prefix tree of changed paths.
4. Rewrite only changed subtrees and their ancestors.
5. Reuse unchanged tree object ids.
6. Create blobs for add/update changes.
7. Remove entries for delete changes.
8. Sort tree entries in Git canonical order.
9. Create the new root tree.
10. Create a commit with the base commit as parent.

If all requested changes produce the same root tree as the base commit, apply
the configured no-op policy:

- `RETURN_NO_OP`: return success without pushing a commit.
- `FORCE_AUDIT_COMMIT`: create a new commit with the same tree only when
  explicitly allowed.
- `FAIL_NO_OP`: return a typed no-op failure.

## Receive-Pack Behavior

The receive-pack command should use expected old id and new commit id for one
target ref. It should select only capabilities that are implemented and tested:

- `report-status` or `report-status-v2`;
- `side-band-64k` when supported;
- `delete-refs` only when branch deletion is later supported;
- `atomic` is not required while updating one ref.

The client must parse:

- unpack ok;
- unpack error;
- per-ref ok;
- per-ref reject;
- side-band progress;
- side-band fatal errors.

Remote hook rejections and protected-branch failures should be surfaced as typed
remote rejects without exposing credentials or file contents.

## Stale-Ref Retry

Default behavior is fail-fast on stale ref.

An optional one-shot retry may be allowed only when:

- the remote reject is a stale-ref conflict;
- the request declares retry allowed;
- all changes are path-based add/update/delete operations;
- the new base can be fetched within limits;
- no expected previous blob id is violated on the new base.

Retry must rebuild the root tree and commit from the new base. It must not reuse
the old commit or force-push over the newer remote state.

## Branch Creation

Branch creation should be explicit:

- disabled by default for ACL storage unless configured;
- allowed only when expected old commit id is the all-zero id or absent under a
  branch-creation policy;
- creates a root commit with the requested files;
- rejects delete-only requests on an absent branch;
- initializes only the requested ref, not unrelated refs or HEAD.

## ACL Integration

Remote ACL save should use `saveFiles(...)` when more than one configured ACL
file belongs to the same remote/ref/credential group.

The ACL layer should:

- pass the commit id from the last batch load as expected old commit id;
- mark required and optional ACL files before building changes;
- save all changed ACL files in one commit;
- fail clearly when the loaded snapshot version is missing;
- avoid JGit fallback in native-only mode;
- report stale-ref conflicts so the caller can reload and retry.

## Limits and Cancellation

Enforce:

- maximum number of changed paths;
- maximum normalized path length;
- maximum path depth;
- maximum input bytes per file;
- maximum total input bytes;
- maximum generated object count;
- maximum outgoing pack bytes;
- total request timeout;
- cancellation before and during network writes.

Cancellation after the pack has been sent may leave the remote result unknown.
Return an indeterminate outcome and require callers to reload the ref before
retrying.

## Observability

Record safe diagnostics:

- remote host and sanitized repository identifier;
- transport type;
- protocol version;
- selected receive-pack capabilities;
- changed path count;
- generated object count;
- outgoing pack byte count;
- old and new commit ids;
- remote status category;
- stale-ref retry count;
- elapsed time.

Never log credentials, authorization headers, raw remote URLs containing embedded
credentials, file contents, or full ACL payloads.

## Implementation Plan

### Phase 1: Request and Result Model

- Add batch request, file change, no-op policy, branch creation policy, and
  typed failure classes.
- Add validation for paths, duplicates, overlaps, modes, and size limits.
- Add dependency boundary tests proving production code does not depend on JGit.

### Phase 2: Shared Base Snapshot

- Reuse remote batch fetch primitives to resolve the base commit and load tree
  objects for all changed paths.
- Add explicit branch creation handling for absent refs.
- Test branch, full-ref, missing-ref, and explicit expected-old behavior.

### Phase 3: Multi-Path Tree Rewrite

- Build a change prefix tree and rewrite only changed subtrees.
- Reuse unchanged object ids and canonical tree sorting.
- Test add, update, delete, nested paths, shared parent directories, executable
  files, symlink policy, and submodule rejection.

### Phase 4: Commit and Pack Build

- Create blobs, trees, and one commit for the whole change set.
- Build one outgoing pack containing only required new objects.
- Enforce generated object and outgoing pack limits.
- Test pack parseability and object id correctness.

### Phase 5: Receive-Pack Integration

- Send one compare-and-set ref update and one pack.
- Parse report-status and side-band responses.
- Test success, remote unpack error, hook reject, and per-ref reject.

### Phase 6: No-Op and Branch Creation Policies

- Implement `RETURN_NO_OP`, `FORCE_AUDIT_COMMIT`, and `FAIL_NO_OP`.
- Implement disabled and explicit branch creation modes.
- Test absent branch, delete-only absent branch, and same-tree updates.

### Phase 7: Stale-Ref Retry

- Add optional one-shot retry for safe path-based conflicts.
- Refetch the base, revalidate expected previous blob ids, rebuild objects, and
  push again.
- Test retry success and retry refusal when changes conflict.

### Phase 8: ACL Save Integration

- Group configured ACL saves by remote/ref/credential.
- Use `saveFiles(...)` for multi-file snapshots in native-only mode.
- Test atomic multi-file ACL save and no JGit fallback.

### Phase 9: Observability and Redaction

- Add metrics and structured diagnostic events.
- Redact credentials, remote URLs, and file contents.
- Test safe error messages for auth, stale ref, hook reject, and size failures.

## Verification Plan

- Batch request validation tests for paths, duplicates, overlaps, modes, and
  size limits.
- Dependency boundary test for no production JGit dependency.
- Base snapshot tests for branch, full ref, missing ref, and branch creation.
- Tree rewrite tests for add, update, delete, nested paths, and shared parents.
- Canonical tree ordering and object id tests.
- Outgoing pack parseability tests.
- Scripted receive-pack tests for success, unpack failure, per-ref reject, and
  side-band fatal errors.
- No-op policy tests.
- Stale-ref fail-fast and one-shot retry tests.
- ACL integration tests proving multiple configured files save atomically in one
  commit.
- Cancellation tests for pre-send cancellation and indeterminate post-send
  cancellation.
- Redaction tests proving credentials and file contents are absent from logs and
  exceptions.

## Acceptance Criteria

- `saveFiles(...)` updates multiple remote files in one commit and one ref
  update without JGit.
- The operation is compare-and-set against one expected old commit id.
- Overlapping and invalid paths are rejected before mutation.
- Tree rewriting reuses unchanged objects and creates only required new objects.
- Stale refs fail by default and retry only under an explicit safe policy.
- Remote ACL storage can save multi-file snapshots atomically in native-only mode.
- Diagnostics are useful and redacted.

## Open Questions

- Should ACL storage allow branch creation by default in development mode only?
- Should missing deletes be treated as no-op or as a conflict?
- Should mode-only updates be part of the first implementation?
- Should same-tree audit commits be allowed for ACL changes?
- Should the batch push API expose per-path expected previous blob ids in the
  public API or keep them internal to ACL storage?

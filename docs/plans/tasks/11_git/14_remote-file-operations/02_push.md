# Native Remote Git Single File Push

## Goal

Add a JGit-free way to update one file in a remote Git repository through the Git
protocol.

The first production use case is saving small configuration files, such as remote
ACL bootstrap data, without cloning a worktree, without shelling out to `git`, and
without using JGit in the implementation path.

## Protocol Reality

Git receive-pack accepts ref updates plus a pack containing the objects needed by
those updates. It does not provide a command that says "write this path".

To update one file by path, Orion must:

1. resolve the current target ref;
2. load enough commit and tree data to build a new tree;
3. create a new blob for the file content;
4. create new tree objects for the changed path and its parent directories;
5. create a new commit object with the previous commit as parent;
6. build a pack containing the new objects;
7. send receive-pack commands with expected old id and new commit id;
8. parse the remote command result and report success or conflict.

The ref update must be compare-and-set style. If the remote ref changed between
read and push, Orion should fail with a typed stale-ref conflict unless an
explicit retry policy is configured.

## Current State

`RemoteGitAccessControlStorage.save()` currently uses JGit to fetch, checkout,
write files to a worktree, commit, and push. That behavior is useful as a
reference, but it keeps remote ACL writes tied to JGit and a local working tree.

The native remote single-file fetch plan covers ref discovery, commit and tree
fetching, path resolution, and blob loading. This push plan depends on those
read-side primitives for discovering the base commit and base tree.

The Git pack build-from-changes plan covers JGit-free blob, tree, commit, and
pack creation. This push plan depends on that object creation and pack builder
work for the outgoing receive-pack payload.

There is no native receive-pack client implementation in Orion yet.

## Non-Goals

Do not implement full clone, checkout, merge, rebase, or working-tree semantics.

Do not implement push for arbitrary local repository history in this phase.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code. Tests may compare behavior against Git
CLI fixtures or a controlled Git server.

Do not silently retry or overwrite a remote branch after a stale-ref conflict.

Do not implement force-push by default. Any forced update must require an explicit
request flag and separate access control decision.

Do not implement Git LFS upload. If the caller writes LFS pointer bytes, Orion
pushes those pointer bytes as an ordinary Git blob.

## Public API

Introduce a write-side client boundary:

```text
NativeRemoteGitFileClient.saveFile(request) -> RemoteGitFilePushResult
```

Request fields:

- remote URI;
- target branch, full ref, or explicit expected commit id plus ref;
- file path inside the Git tree;
- file mode, defaulting to regular file;
- new file bytes or delete intent;
- author and committer identity;
- commit message;
- optional authentication reference;
- expected old commit id, if known;
- connect, read, write, and total operation timeouts;
- maximum outgoing pack bytes;
- maximum input file bytes;
- stale-ref retry policy;
- force update flag, default false.

Result fields:

- ref name;
- old commit id;
- new commit id;
- new blob id or deleted path marker;
- pushed object ids;
- outgoing pack hash and size;
- remote receive status;
- whether a retry was used.

Use typed failures for missing ref, stale ref, missing base object, unsupported
file type, invalid path, authentication failure, protocol error, pack build
failure, remote reject, configured size limit, and unsupported transport.

## Transport Scope

Reuse the transport boundary from the native remote fetch client.

Concrete transports should be added in the same order unless a production need
requires otherwise:

1. scripted in-memory receive-pack server for protocol tests;
2. `git://` receive-pack only for controlled test environments where receive-pack
   is explicitly enabled;
3. smart HTTP(S) receive-pack with username/password or token authentication;
4. SSH receive-pack with private key and known-hosts validation.

The production code should share pkt-line, capability parsing, side-band, and
transport error handling with the fetch client.

## Receive-Pack Client

Add backend-independent receive-pack primitives:

- service discovery and capability advertisement parsing;
- command writer for old id, new id, and ref name;
- capability selection for report-status, delete-refs, side-band-64k, quiet, and
  push-options when needed;
- pack stream writer;
- side-band progress and error parser;
- report-status parser for unpack status and per-ref command status.

Start with one branch update command per request. Multi-ref push can be added
later for batch writes or coordinated updates.

## Update Algorithm

The default safe update path:

1. Use native fetch/ref discovery to resolve the target ref.
2. If the caller supplied an expected old commit id, verify it matches the remote
   ref before building objects.
3. Fetch the base commit and tree data needed for the requested path.
4. Normalize and validate the path.
5. Build a new blob unless the operation is delete.
6. Rebuild only the affected tree objects from the changed path up to the root.
7. Create a new commit object with the old commit as parent.
8. Build a no-delta pack containing new blob, tree, and commit objects.
9. Open receive-pack and send one ref update command.
10. Stream the pack.
11. Parse report-status and return the new commit metadata.

For a missing remote branch, the client may create a root commit only when the
request explicitly allows branch creation. Otherwise return missing ref.

## Path and Tree Semantics

Path normalization should match the read-side client:

- reject absolute paths;
- reject empty paths;
- reject `..` traversal;
- normalize separators to `/`;
- preserve case exactly.

Supported write targets:

- regular file blob;
- executable file blob when requested;
- symlink blob only when the caller explicitly allows symlinks;
- delete of an existing regular file, executable file, or symlink.

Unsupported in the first phase:

- modifying a submodule gitlink;
- replacing a directory with a file unless explicitly allowed;
- replacing a file with a directory;
- preserving platform-specific file attributes.

Deleting a missing path should be configurable: either no-op commit suppression
or typed missing-path failure. ACL storage should prefer failure so configuration
mistakes are visible.

## Commit Semantics

The generated commit must be deterministic for explicit inputs:

- tree id from rebuilt root tree;
- parent id from the resolved old commit;
- author identity and timestamp from request;
- committer identity and timestamp from request or service default;
- UTF-8 commit message;
- optional extra headers only if explicitly supported.

If the new tree id equals the old tree id, the default behavior should be
no-op success without pushing a new commit. Callers that require an audit commit
for identical content can opt in later.

## Stale Ref Handling

A stale ref occurs when the remote ref no longer matches the expected old id at
push time.

Default behavior:

- fail with a typed stale-ref conflict;
- include expected old id and actual remote status when available;
- do not retry automatically.

Optional retry behavior:

1. refetch current ref;
2. reload the requested path from the new tree;
3. reapply the same file change;
4. rebuild commit and pack;
5. retry receive-pack once, or up to a configured small limit.

Retries should only be allowed for simple file add/update/delete operations.
Never retry after a remote reject that is not a stale-ref conflict.

## ACL Integration

Use this client to replace the save path of remote ACL bootstrap storage after
the native read path exists.

For ACL storage:

- batch all configured ACL files into one commit when possible;
- require expected old commit id from the loaded snapshot when saving;
- fail save if the snapshot version is missing unless branch creation is
  explicitly configured;
- do not fall back to JGit in native-only mode;
- report stale-ref conflicts clearly so the caller can reload and retry.

The first integration can remain single-file internally only if ACL configuration
has one file. Multi-file ACL snapshots should wait for a batch push API so saves
are atomic across all configured paths.

## Batch Follow-Up

The detailed batch push design now lives in
the batch-push plan embedded below.

## Phased Plan

Phase 1: API and dependency boundary.

Define request, result, failure, and receive-pack transport interfaces. Add a
boundary test that the production implementation module has no `org.eclipse.jgit`
dependency.

Phase 2: Receive-pack protocol fixtures.

Implement scripted receive-pack server fixtures for advertisement, command
handling, pack capture, side-band progress, successful report-status, unpack
error, and per-ref reject.

Phase 3: Command writer and report-status parser.

Write one ref update command, select minimal capabilities, parse unpack status,
parse per-ref status, and map remote rejects to typed errors.

Phase 4: Object creation from one file change.

Use the Git object builders to create blob, tree, and commit objects for an
add/update/delete against a fetched base tree.

Phase 5: Outgoing pack construction.

Build a no-delta pack for the new objects, enforce outgoing size limits, and
verify the pack can be parsed by Orion and accepted by a controlled Git server.

Phase 6: Single-branch push.

Send the receive-pack command and pack over the scripted transport, then over the
first real transport selected for implementation. Verify new remote ref and file
content through native read-back.

Phase 7: Conflict handling.

Cover stale ref, missing branch, branch creation, no-op update, remote unpack
failure, hook rejection, and optional one-shot retry.

Phase 8: ACL save integration.

Wire remote ACL save to the native client in native-only mode for the supported
single-file case. Keep multi-file saves behind the batch follow-up unless atomic
batch writes are already implemented.

Phase 9: Batch file push.

Add `saveFiles` support for multiple file changes in one tree, one commit, and
one ref update. Use this for multi-file ACL snapshots.

Phase 10: Observability and limits.

Record negotiated capabilities, old and new commit ids, outgoing pack size,
object count, remote status, conflict category, and retry count without logging
credentials or file contents.

## Open Questions

Should smart HTTP(S) be implemented before SSH for real receive-pack support,
because hosted Git providers commonly expose HTTPS token auth?

Should branch creation be allowed for ACL bootstrap by default, or only with an
explicit configuration flag?

Should no-op saves return success without a commit, or should callers be able to
force an audit commit with identical tree content?

How should author and committer timestamps be supplied in production so tests can
remain deterministic?

Should native push require a snapshot version for all updates, or allow
best-effort updates against the latest remote ref?

Where should outgoing pack bytes be buffered before streaming: memory, temporary
file, or direct streaming from the pack builder?

## Verification

Cover at least these cases:

- production module has no `org.eclipse.jgit` dependency;
- receive-pack advertisement parser reads capabilities and refs;
- command writer sends expected old id, new id, ref name, and selected
  capabilities;
- report-status parser handles ok, unpack error, rejected ref, and side-band
  errors;
- update one existing regular file and read it back through the native fetch
  client;
- add a new nested file and build the expected parent tree objects;
- delete an existing file and remove empty parent trees where policy requires it;
- executable mode is preserved when requested;
- symlink writes are rejected by default and accepted only when enabled;
- submodule gitlink modification is rejected;
- no-op content update does not push a new commit by default;
- stale expected old id fails without changing the remote ref;
- optional retry refetches, rebuilds, and succeeds for a simple non-conflicting
  update;
- missing branch fails unless branch creation is explicitly allowed;
- branch creation produces a root commit when allowed;
- outgoing pack size limit is enforced before streaming;
- remote unpack failure and hook rejection produce typed failures;
- credentials and file contents never appear in logs or exception messages;
- ACL single-file save can run in native-only mode without creating a worktree;
- batch follow-up saves multiple ACL files atomically in one commit.

---

## Native Remote Git Batch File Push

### Goal

Add a JGit-free way to update multiple files in the same remote Git repository
and ref through one Git receive-pack operation.

The first production use case is remote ACL bootstrap storage. When ACL state is
split across several configured files, Orion must save those files atomically in
one commit instead of issuing separate pushes that can leave the remote snapshot
partially updated.

### Current State

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

### Non-Goals

- Do not implement arbitrary local repository push or history synchronization.
- Do not clone, checkout, merge, rebase, or use a working tree.
- Do not shell out to the `git` executable in production code.
- Do not depend on JGit in production code.
- Do not implement force-push by default.
- Do not implement Git LFS upload. If callers write LFS pointer bytes, push them
  as ordinary Git blobs.
- Do not add multi-ref transactions in this step. One request updates one ref.

### Public API

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

### Atomic Semantics

The batch operation must create one new root tree, one commit, one outgoing pack,
and one compare-and-set ref update.

All requested changes either become visible together at the new commit or none of
them become visible. If receive-pack rejects the ref update, the method reports a
typed failure and must not claim partial success.

The operation should preserve one resolved base commit for the whole request. It
must not refetch or resolve the branch independently for each path.

### Change Validation

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

### Tree Rewrite Algorithm

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

### Receive-Pack Behavior

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

### Stale-Ref Retry

Default behavior is fail-fast on stale ref.

An optional one-shot retry may be allowed only when:

- the remote reject is a stale-ref conflict;
- the request declares retry allowed;
- all changes are path-based add/update/delete operations;
- the new base can be fetched within limits;
- no expected previous blob id is violated on the new base.

Retry must rebuild the root tree and commit from the new base. It must not reuse
the old commit or force-push over the newer remote state.

### Branch Creation

Branch creation should be explicit:

- disabled by default for ACL storage unless configured;
- allowed only when expected old commit id is the all-zero id or absent under a
  branch-creation policy;
- creates a root commit with the requested files;
- rejects delete-only requests on an absent branch;
- initializes only the requested ref, not unrelated refs or HEAD.

### ACL Integration

Remote ACL save should use `saveFiles(...)` when more than one configured ACL
file belongs to the same remote/ref/credential group.

The ACL layer should:

- pass the commit id from the last batch load as expected old commit id;
- mark required and optional ACL files before building changes;
- save all changed ACL files in one commit;
- fail clearly when the loaded snapshot version is missing;
- avoid JGit fallback in native-only mode;
- report stale-ref conflicts so the caller can reload and retry.

### Limits and Cancellation

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

### Observability

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

### Implementation Plan

#### Phase 1: Request and Result Model

- Add batch request, file change, no-op policy, branch creation policy, and
  typed failure classes.
- Add validation for paths, duplicates, overlaps, modes, and size limits.
- Add dependency boundary tests proving production code does not depend on JGit.

#### Phase 2: Shared Base Snapshot

- Reuse remote batch fetch primitives to resolve the base commit and load tree
  objects for all changed paths.
- Add explicit branch creation handling for absent refs.
- Test branch, full-ref, missing-ref, and explicit expected-old behavior.

#### Phase 3: Multi-Path Tree Rewrite

- Build a change prefix tree and rewrite only changed subtrees.
- Reuse unchanged object ids and canonical tree sorting.
- Test add, update, delete, nested paths, shared parent directories, executable
  files, symlink policy, and submodule rejection.

#### Phase 4: Commit and Pack Build

- Create blobs, trees, and one commit for the whole change set.
- Build one outgoing pack containing only required new objects.
- Enforce generated object and outgoing pack limits.
- Test pack parseability and object id correctness.

#### Phase 5: Receive-Pack Integration

- Send one compare-and-set ref update and one pack.
- Parse report-status and side-band responses.
- Test success, remote unpack error, hook reject, and per-ref reject.

#### Phase 6: No-Op and Branch Creation Policies

- Implement `RETURN_NO_OP`, `FORCE_AUDIT_COMMIT`, and `FAIL_NO_OP`.
- Implement disabled and explicit branch creation modes.
- Test absent branch, delete-only absent branch, and same-tree updates.

#### Phase 7: Stale-Ref Retry

- Add optional one-shot retry for safe path-based conflicts.
- Refetch the base, revalidate expected previous blob ids, rebuild objects, and
  push again.
- Test retry success and retry refusal when changes conflict.

#### Phase 8: ACL Save Integration

- Group configured ACL saves by remote/ref/credential.
- Use `saveFiles(...)` for multi-file snapshots in native-only mode.
- Test atomic multi-file ACL save and no JGit fallback.

#### Phase 9: Observability and Redaction

- Add metrics and structured diagnostic events.
- Redact credentials, remote URLs, and file contents.
- Test safe error messages for auth, stale ref, hook reject, and size failures.

### Verification Plan

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

### Acceptance Criteria

- `saveFiles(...)` updates multiple remote files in one commit and one ref
  update without JGit.
- The operation is compare-and-set against one expected old commit id.
- Overlapping and invalid paths are rejected before mutation.
- Tree rewriting reuses unchanged objects and creates only required new objects.
- Stale refs fail by default and retry only under an explicit safe policy.
- Remote ACL storage can save multi-file snapshots atomically in native-only mode.
- Diagnostics are useful and redacted.

### Open Questions

- Should ACL storage allow branch creation by default in development mode only?
- Should missing deletes be treated as no-op or as a conflict?
- Should mode-only updates be part of the first implementation?
- Should same-tree audit commits be allowed for ACL changes?
- Should the batch push API expose per-path expected previous blob ids in the
  public API or keep them internal to ACL storage?

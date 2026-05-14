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

Add a batch API after the single-file path works:

```text
saveFiles(remote, ref, changes) -> RemoteGitFilePushResult
```

The batch operation should:

- resolve the base commit once;
- rebuild the root tree once;
- create one commit for all file changes;
- push one pack and one ref update;
- reject overlapping paths such as `a` and `a/b`;
- keep all configured ACL files atomic in one commit.

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

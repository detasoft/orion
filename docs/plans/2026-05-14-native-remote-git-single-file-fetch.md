# Native Remote Git Single File Fetch

## Goal

Add a JGit-free way to read one file from a remote Git repository through the Git
protocol.

The first production use case is read-only loading of a small configured file,
such as ACL bootstrap data, without cloning a worktree and without using JGit in
the implementation path.

## Protocol Reality

Git upload-pack does not provide a command that says "send this path from this
ref". The protocol exchanges refs and Git objects. To read a file by path, Orion
must:

1. resolve the requested ref to a commit id;
2. fetch enough commit and tree objects to resolve the file path;
3. find the blob id for the path;
4. fetch or extract that blob;
5. return the blob bytes plus the commit/object metadata.

Efficient single-file transfer requires server support for partial clone style
filtering, especially protocol v2 `fetch` with `filter blob:none`, and the
ability to fetch the wanted blob object after the path is resolved. Not every Git
server supports that combination. Orion should therefore expose a strict mode
that fails when the remote cannot serve the file efficiently, and a compatibility
mode that may download a larger shallow pack when necessary.

## Current State

`RemoteGitAccessControlStorage` currently uses JGit commands to clone, fetch,
checkout, read files from a worktree, commit, and push. That gives a working
behavioral reference for remote ACL storage, but it is not acceptable for the
native single-file read path because it imports JGit APIs and materializes a
worktree.

Existing native Git transport plans focus on Orion serving repositories over a
native listener. They do not define a client-side upload-pack implementation for
remote repositories.

The pack parser, pack index, and object lookup plans define the lower-level
pieces needed to parse returned pack data, but they do not define remote
negotiation, ref discovery, path resolution, or partial blob fetch.

## Non-Goals

Do not implement remote Git writes or push in this phase.

Do not clone a full worktree as the implementation strategy.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code. Tests may compare against Git CLI
behavior or golden protocol fixtures; avoid JGit in this feature's implementation
module.

Do not guarantee minimal network transfer against servers that do not advertise
the required protocol capabilities. Report that limitation explicitly.

Do not fetch Git LFS object contents. If a file is an LFS pointer, return the
pointer bytes; LFS download is a separate feature.

## Public API

Introduce a small read-only client boundary:

```text
NativeRemoteGitFileClient.loadFile(request) -> RemoteGitFileSnapshot
```

Request fields:

- remote URI;
- target ref, branch, tag, or explicit commit id;
- repository path when the URI scheme requires it;
- file path inside the Git tree;
- optional authentication reference;
- connect, read, and total operation timeouts;
- maximum pack bytes;
- maximum returned file bytes;
- strict minimal-transfer flag;
- allowed protocol versions and transports.

Snapshot fields:

- resolved commit id;
- blob object id;
- Git file mode;
- file bytes;
- whether transfer used partial clone filtering;
- pack ids or temporary object ids used for diagnostics.

Use typed failures for missing ref, missing path, unsupported file type,
unsupported remote capability, authentication failure, protocol error, pack parse
failure, and configured size limit.

## Transport Scope

Start with protocol v2 upload-pack over a testable transport abstraction.

Concrete transports can be added incrementally:

1. in-memory scripted upload-pack server for tests;
2. `git://` TCP upload-pack for unauthenticated local or controlled remotes;
3. smart HTTP(S) upload-pack with username/password or token authentication;
4. SSH upload-pack with configured private key and known-hosts validation.

All transports should expose the same packet-line stream boundary to the protocol
client. Authentication, TLS, host-key checks, proxy behavior, and redirects stay
behind the transport adapter.

## Protocol Client

Add backend-independent Git protocol primitives:

- pkt-line encoder and decoder;
- flush, delimiter, and response-end packet handling;
- capability advertisement parser;
- side-band and side-band-64k demultiplexer;
- protocol v2 command writer;
- upload-pack error parser.

Implement these protocol v2 commands first:

- `ls-refs` with ref-prefix filtering for the requested branch, tag, or ref;
- `fetch` with `want`, `done`, `deepen 1` where supported, and optional
  `filter blob:none`;
- packfile stream extraction from side-band responses.

Protocol v0/v1 can be added later only as a compatibility fallback. The first
client should prefer protocol v2 because capability discovery and filtering are
cleaner there.

## Efficient Read Algorithm

Preferred path when the server supports protocol v2 filtering:

1. Connect to upload-pack and negotiate protocol v2.
2. Run `ls-refs` for the requested ref.
3. Fetch the resolved commit with `filter blob:none`.
4. Parse the returned pack into transient commit and tree objects.
5. Resolve the requested file path through tree entries.
6. Fetch the resolved blob object id.
7. Parse the blob from the returned pack.
8. Return the blob bytes with commit id, blob id, and mode.

The transient object store can be in-memory for the first implementation, guarded
by pack and object size limits. It should use the Orion-owned pack parser and Git
object parsers, not JGit.

## Compatibility Fallback

When the remote does not support filtering, Orion may fetch a shallow pack for
the target ref without `filter blob:none`, then parse that pack and extract the
requested blob if present.

When the remote supports filtering but rejects direct blob wants, Orion should
return a typed unsupported-capability error in strict mode. In compatibility mode
it may retry with a larger shallow fetch if the configured pack size limit allows
it.

The client must never silently change from "single-file efficient fetch" to
"download a large repository" without recording that fallback in the result or
failure.

## Git Object Parsing

Add enough Git object parsing to support path resolution:

- commit parser for tree id, parents, author, committer, encoding, and message
  boundary;
- tree parser for entry mode, path segment, and object id;
- blob loader with configured size limit;
- tag peeling for annotated tags that point to commits.

Path normalization must reject absolute paths, empty paths, and `..` traversal.
Tree lookup must distinguish:

- missing path;
- directory requested as file;
- symlink blob;
- regular file blob;
- executable file blob;
- gitlink/submodule entry.

For symlinks, return the symlink blob bytes and mode unless a caller explicitly
requires regular files only. For gitlinks, return an unsupported file type error.

## ACL Integration

Use this client first as a read-only replacement for the load path of remote ACL
bootstrap storage.

`RemoteGitAccessControlStorage.load()` currently fetches and checks out a whole
worktree through JGit. A native read implementation should instead call the
single-file client for each configured ACL path, preferably batching paths that
share the same remote and ref so commit/tree objects are fetched once.

Saving remote ACL data still requires native object creation, pack building,
ref update negotiation, and push. Until that exists, a no-JGit storage mode
should either be read-only or fail save attempts with a clear unsupported error.
It should not fall back to JGit when configured for native-only operation.

## Batching Follow-Up

Although the first API can load one file, ACL configuration often contains more
than one path. The detailed batch design now lives in
`docs/plans/2026-05-16-native-remote-git-batch-file-fetch.md`.

## Phased Plan

Phase 1: API and module boundary.

Define request, result, failure, and transport interfaces. Add a boundary test
that the production module does not depend on `org.eclipse.jgit`.

Phase 2: Protocol primitives.

Implement pkt-line read/write, protocol v2 response parsing, side-band
demultiplexing, and scripted protocol fixtures.

Phase 3: Ref discovery.

Implement protocol v2 `ls-refs` for branch, tag, full ref, and explicit commit
inputs. Add annotated tag peeling behavior.

Phase 4: Filtered commit and tree fetch.

Implement upload-pack `fetch` with `filter blob:none`, `deepen 1` when supported,
and pack stream capture. Parse commit and tree objects from the returned pack.

Phase 5: Path resolver.

Resolve normalized file paths through parsed trees. Cover regular files,
executable files, symlinks, directories, missing paths, nested paths, and
submodules.

Phase 6: Blob fetch.

Fetch the resolved blob id, parse the returned pack, enforce file size limits,
and return the final snapshot.

Phase 7: Compatibility fallback.

Add no-filter shallow fetch fallback and strict-mode failure behavior for remotes
that do not support efficient single-blob reads.

Phase 8: Real transports.

Add `git://` first, then smart HTTP(S), then SSH. Keep auth and host validation
behind transport-specific code.

Phase 9: ACL read integration.

Replace remote ACL `load()` with the native client in native-only mode. Add a
batch read path for multiple ACL files sharing the same ref.

Phase 10: Observability and limits.

Record negotiated protocol version, remote capabilities, filter usage, fallback
mode, transferred pack sizes, returned file size, and failure category without
logging credentials or file contents.

## Open Questions

Should the first production transport be smart HTTP(S) because hosted Git
providers commonly expose it, or `git://` because it is the simplest protocol
shape for a controlled test server?

Should strict minimal-transfer be the default for ACL bootstrap, or should ACL
loads allow a bounded shallow-pack fallback?

Where should transient fetched objects live: only memory, temporary files for
large packs, or the future pack storage layer?

Should native remote reads support explicit commit ids before branch and tag
resolution, or only advertised refs at first?

How should credentials be represented so this client can reuse the same secret
reference model as remote ACL storage?

Should `loadFile()` return symlink bytes by default, or reject symlinks unless
the caller opts in?

## Verification

Cover at least these cases:

- production module has no `org.eclipse.jgit` dependency;
- pkt-line decoder handles data packets, flush, delimiter, response-end,
  malformed lengths, and truncated packets;
- protocol v2 `ls-refs` resolves branch, tag, and full ref names;
- annotated tag pointing to a commit is peeled before path resolution;
- filtered fetch obtains commit and tree objects without blob contents when the
  scripted server advertises `filter`;
- path resolver finds a nested regular file and returns the expected blob id;
- missing path, directory path, and submodule path produce typed failures;
- blob fetch returns exact bytes and enforces maximum file size;
- strict mode fails when the remote lacks filtering support;
- compatibility mode can extract a file from a bounded shallow no-filter fetch;
- side-band progress and error packets are handled without corrupting pack data;
- credentials never appear in logs or exception messages;
- remote ACL load can read configured files without creating a worktree;
- batch follow-up reuses commit/tree fetches for multiple requested paths.

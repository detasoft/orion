# Native Git Object Model and Path Resolution

## Goal

Add a JGit-free Git object model, object parsers, object builders, and tree path
resolution layer.

This layer should let Orion parse commit, tree, tag, and blob objects from packs,
resolve a repository path to a blob id, rebuild trees for small file changes, and
compute canonical Git object ids without importing objects through JGit.

## Current State

Orion still relies on JGit for most object-level behavior. `JGitRepository`
loads files by walking JGit commits and trees, saves files with JGit
`ObjectInserter`, and writes commits through JGit `CommitBuilder`.

The pack parsing plans define how to read raw pack entries, but they intentionally
separate pack entry parsing from object interpretation and delta resolution.

The native remote single-file fetch plan needs commit and tree parsing to resolve
a file path to a blob id. The native remote single-file push plan needs object
builders and tree rewriting to create blobs, trees, commits, and outgoing packs.

The commit information model plan needs a rebuildable projection over canonical
Git objects. That projection should consume this object layer rather than JGit.

## Non-Goals

Do not implement Git protocol negotiation here.

Do not parse pack headers, indexes, or side-band streams here. This layer consumes
inflated Git object bytes and produces canonical object bytes.

Do not implement a full working tree, index, merge, rebase, or checkout engine.

Do not depend on JGit in production code. Tests may compare object ids and parsed
fields against JGit or Git CLI fixtures.

Do not implement SHA-256 repositories in the first step. Keep the model shaped so
hash algorithms can be added later, but start with SHA-1 object ids because the
current code and tests use SHA-1.

Do not apply Git attributes, filters, CRLF conversion, or LFS smudging. Blob
bytes are canonical Git blob bytes.

## Object Model

Introduce small immutable value objects:

- `GitObjectId`: hash algorithm plus hex value;
- `GitObjectType`: commit, tree, blob, tag;
- `GitObjectHeader`: type and content size;
- `GitObjectRecord`: object id, type, canonical content bytes or bounded content
  handle;
- `GitCommitObject`: tree id, parent ids, author, committer, encoding, message,
  and extra headers;
- `GitTreeObject`: sorted tree entries;
- `GitTreeEntry`: mode, name, object id, object type hint;
- `GitTagObject`: target id, target type, tag name, tagger, message, and extra
  headers;
- `GitBlobObject`: object id, size, and content handle.

Keep object model classes independent from storage backends, protocol clients,
and JGit classes.

## Canonical Object Format

Support canonical Git object identity:

```text
<type> <size>\0<content>
```

The object id calculator should stream the header and content into the selected
hash algorithm. The first implementation can expose SHA-1 only, but the API
should avoid hard-coding 20-byte assumptions outside the SHA-1 implementation.

All builders must produce canonical content bytes, not pack entry bytes. Pack
entry compression and pack entry headers remain the responsibility of the pack
builder.

## Object Parsers

Add parsers for inflated object content:

Commit parser:

- tree id;
- zero or more parent ids;
- author identity line;
- committer identity line;
- optional encoding;
- optional extra headers, including multiline continuations;
- blank-line boundary;
- raw message bytes or UTF-8 message view.

Tree parser:

- mode bytes;
- entry name bytes;
- NUL separator;
- object id bytes;
- entry ordering validation;
- duplicate entry detection;
- invalid name detection.

Tag parser:

- object id;
- target type;
- tag name;
- tagger line when present;
- extra headers;
- blank-line boundary;
- raw message bytes.

Blob parser:

- bounded content handling;
- size validation against object header or caller metadata;
- no text conversion.

Parser errors should include object id when known, type, byte offset, and the
violated invariant.

## Object Builders

Add builders that create canonical content bytes and object ids:

- blob builder from raw bytes or bounded stream;
- tree builder from explicit entries;
- commit builder from explicit metadata;
- tag builder from explicit metadata.

Tree builder rules:

- entries sorted by Git tree ordering;
- duplicate names rejected;
- unsupported or invalid modes rejected;
- path separators rejected inside a single tree entry name;
- names with NUL rejected.

Commit builder rules:

- explicit author and committer;
- deterministic timestamps in tests;
- parent order preserved;
- message normalized only by explicit caller choice;
- extra headers preserved in explicit order if supported.

Builders should return both canonical content bytes and object ids so pack
building can consume the result directly.

## Path Normalization

Add a Git path normalizer shared by remote fetch, remote push, ACL storage, and
future repository reads:

- reject null, blank, and empty paths;
- reject absolute paths;
- reject `..` traversal;
- normalize platform separators to `/`;
- collapse redundant `.` segments;
- preserve case exactly;
- reject NUL and empty path segments;
- optionally reject trailing slash for file reads.

The normalizer should return a value object such as `GitPath` rather than a raw
string after validation.

## Tree Path Resolver

Add a resolver that walks parsed tree objects through a caller-provided object
loader:

```text
resolve(rootTreeId, GitPath) -> GitResolvedPath
```

The object loader boundary should be small:

```text
loadTree(objectId) -> GitTreeObject
loadObjectHeader(objectId) -> GitObjectHeader
```

Resolution results should distinguish:

- regular file blob;
- executable file blob;
- symlink blob;
- directory;
- missing path;
- gitlink/submodule;
- wrong object type;
- corrupted tree.

The resolver should not load blob content by default. It should resolve to the
blob id and mode, then let the caller decide whether to fetch or load the blob
bytes.

## Tree Rewriter

Add a small tree rewriting component for file add, update, and delete:

```text
rewrite(rootTreeId, List<GitFileChange>) -> GitTreeRewriteResult
```

The rewriter should:

- load only tree objects along changed paths;
- preserve unchanged tree entries and object ids;
- create new tree objects bottom-up;
- return the new root tree id and all newly created tree objects;
- reject overlapping changes such as `a` and `a/b`;
- optionally remove empty directories after deletes;
- reject replacing a directory with a file unless explicitly allowed;
- reject replacing a file with a directory in the first implementation.

This is not a merge engine. It applies changes to one known base tree.

## Delta Resolution Boundary

Raw pack parsing can expose delta entries before final object content is known.
This object layer should accept already reconstructed inflated object content.

Add a narrow future integration point:

```text
GitObjectContentProvider.loadInflatedObject(objectId)
```

Delta resolution can later feed reconstructed content into the same object
parsers and id calculator.

## Storage Integration

Keep storage behind small loader/writer interfaces:

- in-memory object store for tests and transient remote single-file fetch;
- pack-backed object loader for parsed remote packs;
- local repository object loader later;
- S3 pack object loader later.

The object layer should not decide where objects live. It should validate,
parse, build, and identify objects.

## Phased Plan

Phase 1: Object ids and canonical headers.

Implement SHA-1 `GitObjectId`, object type mapping, canonical header encoding,
and streaming object id calculation.

Phase 2: Blob and tree parsing.

Parse blob metadata and tree entries from deterministic fixtures. Validate tree
ordering, duplicate names, malformed modes, truncated object ids, and invalid
names.

Phase 3: Commit and tag parsing.

Parse commit and tag fields, multiline headers, encoding headers, empty messages,
and malformed header boundaries.

Phase 4: Object builders.

Build blob, tree, commit, and tag canonical content. Verify generated object ids
against Git CLI or JGit fixtures.

Phase 5: Path normalization and tree resolver.

Resolve nested paths from parsed trees without loading blob bytes. Cover regular
files, executable files, symlinks, directories, missing paths, and gitlinks.

Phase 6: Tree rewriter.

Apply one file add, update, delete, nested add, and nested delete to a base tree.
Return new tree objects and preserve unchanged object ids.

Phase 7: Remote single-file fetch integration.

Use commit/tree/tag parsers and the path resolver to turn a fetched commit pack
into a blob id and mode.

Phase 8: Remote single-file push integration.

Use object builders and the tree rewriter to create blob, tree, and commit
objects for outgoing native receive-pack pushes.

Phase 9: Commit information model integration.

Use this object layer to build commit, tree, tag, and path projections from
canonical object storage.

Phase 10: Hash algorithm extension.

Design and test the minimum changes needed for SHA-256 repositories after SHA-1
behavior is stable.

## Open Questions

Should object model classes live in `core/git-common`, a new `core/git-object`
module, or beside the pack parser?

Should parsers expose raw byte slices for headers and messages, decoded strings,
or both?

Should tree entries infer object type from mode immediately, or leave type
resolution to the object loader?

Should symlinks be accepted by default in ACL reads, or should ACL storage
restrict reads and writes to regular files only?

Should tree rewriting preserve empty directories as impossible Git state, or
always remove empty tree objects unless explicitly requested by a caller?

How should large blobs be represented so object id calculation can stream while
small configuration files remain easy to test?

## Verification

Cover at least these cases:

- SHA-1 object id calculation matches Git CLI or JGit for blob, tree, commit, and
  tag fixtures;
- canonical object header encoding handles empty and large content sizes;
- tree parser accepts valid sorted entries and rejects unsorted entries,
  duplicate names, invalid modes, NUL names, and truncated object ids;
- commit parser handles root commits, merge commits, encoding headers, multiline
  extra headers, empty messages, and malformed boundaries;
- tag parser handles annotated tags and malformed target types;
- blob parser enforces configured size limits without text conversion;
- path normalizer rejects absolute paths, empty paths, traversal, NUL bytes, and
  empty path segments;
- tree resolver finds nested regular files and executable files without loading
  blob content;
- tree resolver distinguishes missing path, directory path, symlink, and gitlink;
- tree rewriter updates one file while preserving unrelated tree object ids;
- tree rewriter adds nested files and creates only required parent trees;
- tree rewriter deletes files and handles empty parent tree policy;
- overlapping file changes are rejected deterministically;
- object builders create deterministic commits from explicit identities and
  timestamps;
- remote single-file fetch can resolve a path using this object layer;
- remote single-file push can build new objects using this object layer;
- production object layer has no JGit dependency.

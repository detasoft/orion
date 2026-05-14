# Git Pack Build From Changes

## Goal

Build valid Git pack files from explicit Orion object changes without depending
on JGit as the pack writer.

The first target is deterministic no-delta pack creation from a known set of new
objects. Later phases can add delta selection and byte-for-byte compatibility
with JGit under controlled generation settings.

## Current State

`JGitRepository.saveFiles()` can create blobs, trees, commits, and ref updates
through JGit `ObjectInserter` and related APIs. Receive and upload flows still
delegate to JGit `ReceivePack` and `UploadPack`.

There is no Orion-owned pack builder in the current code. The existing
`saveFiles()` path writes objects into a JGit repository and updates refs through
JGit, so it is a behavior reference for file-to-commit semantics rather than a
pack-building implementation.

S3 repository code contains an `S3ObjectInserter`, but it does not insert
ordinary objects yet. It only creates the JGit-backed `S3PackParser` for incoming
pack streams. `S3ObjectReader` and S3 ref updates are also incomplete, and the
public S3 repository provider reports repository operations as unsupported.

The high-level Git pack plan already calls for pack header generation,
whole-object entry encoding, deterministic ordering, configurable compression,
delta support, and checksum generation.

What is missing is a concrete builder plan that starts from a change set:

- file content changes;
- tree changes;
- commit metadata;
- ref update intent;
- object closure needed for a pack.

## Non-Goals

Do not replace JGit receive-pack or upload-pack in the first builder step.

Do not implement delta selection before deterministic whole-object packs work.

Do not update repository refs directly from the pack builder. Ref updates should
remain a separate operation with expected-old-id checks.

Do not assume S3, filesystem, or any other storage backend in the pack builder.

Do not require byte-for-byte equality with JGit for the first no-delta builder
milestone. Canonical object equality is enough first; byte equality follows once
compression and ordering are controlled.

## Change Model

Introduce explicit change inputs:

- `GitFileChange`: path, mode, content, delete flag;
- `GitTreeChangeSet`: base tree id, file changes;
- `GitCommitSpec`: tree id, parents, author, committer, message, extra headers;
- `GitRefChange`: ref name, expected old id, new id;
- `GitObjectWrite`: object type and canonical object bytes;
- `GitPackBuildRequest`: ordered object writes plus compression settings.

The change model should not depend on JGit classes. Tests may use JGit to create
reference objects and validate the resulting pack.

## Object Creation Pipeline

Use a clear pipeline from changes to pack bytes:

1. Normalize file paths and modes.
2. Create new blob objects for added or updated file content.
3. Rebuild affected tree objects from the base tree plus file changes.
4. Create a commit object from explicit metadata.
5. Determine the new ref id from the commit object id.
6. Select objects that must be included in the pack.
7. Emit a no-delta pack containing those objects.
8. Verify the pack can be parsed by Orion and JGit.

The first implementation may skip base-tree diffing and accept already prepared
`GitObjectWrite` values. The next step can add object creation from file
changes.

## Pack Builder Model

Add builder-facing value objects:

- `GitPackObject`: object id, type, uncompressed canonical bytes;
- `GitPackCompressionSettings`: level, strategy, nowrap behavior if needed;
- `GitPackObjectOrder`: deterministic comparator or explicit order;
- `GitPackBuildResult`: pack hash, object count, total bytes, optional index
  data;
- `GitPackBuildError`: object id, phase, and cause.

The pack builder should write to `OutputStream` and update the pack checksum as
bytes are emitted.

## No-Delta Pack Format

The first builder should support whole-object entries only:

- write `PACK`;
- write version 2;
- write object count;
- for each object, write type and variable-length size header;
- deflate canonical object bytes;
- append trailing SHA-1 checksum over all previous pack bytes.

Object id calculation uses canonical Git object bytes:

```text
<type> <size>\0<content>
```

Pack entry bytes do not include that object header; they include the object
content compressed under the pack entry type and size.

## Object Ordering

Make ordering explicit from the first implementation.

Start with caller-provided order for byte-level tests and a stable default order
for service use. A practical default:

1. commits;
2. tags;
3. trees;
4. blobs;
5. lexical object id within each type.

Do not rely on hash map iteration order.

## Compression

Expose compression settings even if the first default is simple.

Tests comparing bytes must pin compression level and strategy. Tests comparing
canonical object equality can ignore exact compressed bytes and parse the pack
back into objects.

## Delta Follow-Up

After no-delta packs work, add optional delta entries:

- explicit delta source object;
- generated delta instructions;
- `REF_DELTA` first because it is easier to reason about across pack order;
- `OFS_DELTA` after pack offsets are stable;
- policy for when deltas are enabled;
- fallback to whole-object entry if delta is larger or unsafe.

Delta selection should be a separate policy object. The pack writer should only
encode the delta decision it is given.

## Ref Update Integration

Pack creation and ref updates should remain separate.

A higher-level service can:

- build new objects from a change set;
- write or upload the generated pack to storage;
- verify all objects are present;
- update the ref with expected old id;
- publish a receive/update event.

This prevents a pack-building failure from partially updating repository refs.

## Phased Plan

Phase 1: Whole-object pack builder.

Accept explicit `GitPackObject` values and write a valid no-delta pack. Verify
Orion parser and JGit can parse it.

Phase 2: Git object builders.

Add canonical object byte builders for blob, tree, commit, and tag. Validate
object ids against JGit for deterministic fixtures.

Phase 3: Build from file changes.

Given a base tree and file changes, produce new blob, tree, and commit objects.
Start with add/update paths, then add delete and nested directory behavior.

Phase 4: Object closure selection.

Given old and new refs, select only objects needed by a receiver that already
has the old reachable set. Start with explicit known-haves in tests.

Phase 5: Pack storage integration.

Stream the generated pack into the pack storage API, store pack hash and object
count, then parse it back as validation.

Phase 6: Delta support.

Add explicit delta entry encoding, then generated delta selection.

## Open Questions

Should pack building live in `core/git-engine`, a new `core/git-pack` module, or
`core/git-common`?

Should the first object creation path use existing `saveFiles()` semantics as
the reference behavior?

How should base tree data be loaded without JGit once repository storage moves
away from JGit?

Should byte-for-byte equality with JGit be required for no-delta packs, or only
after compression settings are fully controlled?

Should object closure selection use commit graph metadata from the commit
information model?

## Verification

Cover at least these cases:

- build a no-delta pack with one blob and parse it with Orion;
- JGit can parse Orion-built no-delta packs;
- blob, tree, commit, and tag canonical object ids match JGit fixtures;
- deterministic input order produces deterministic pack bytes;
- default ordering is stable across repeated runs;
- pack trailer checksum is valid;
- corrupting one pack byte causes checksum validation failure;
- building from one file add creates blob, tree, commit, and expected ref id;
- building from file update reuses unchanged tree entries where applicable;
- deleting a file removes it from the generated tree;
- nested path changes produce the expected nested tree objects;
- generated pack includes only selected new objects when haves are provided;
- delta-disabled builder never emits `OFS_DELTA` or `REF_DELTA`;
- later delta-enabled builder emits JGit-readable delta packs.

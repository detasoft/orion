# Commit Information Model

## Goal

Create an Orion-owned model of information extracted from Git commits, trees,
tags, and refs, plus fast ways to rebuild and read that model.

This model should let Orion answer common repository questions without walking
raw Git objects through JGit every time. It should be rebuilt from canonical Git
objects and packs, so the derived data can be discarded and regenerated when the
format changes or corruption is suspected.

## Scope

Model the information Orion needs from commits:

- commit id, tree id, parent ids, author, committer, timestamps, timezone,
  message, encoding, and extra headers;
- branch and tag refs, including annotated tag peeling;
- commit graph relationships and reachability from refs;
- tree entries for each commit, including path, mode, object id, type, and size
  when known;
- per-path history for fast "what changed" and "load this file at version"
  queries;
- repository snapshots for selected refs or commits;
- pack and object locations needed to read the underlying bytes.

The first target use cases are fast ACL/config file reads, repository ref
advertisement, fetch negotiation support, and avoiding repeated full tree walks
for unchanged commits.

## Design Notes

Treat the model as a projection, not the source of truth. Git objects and refs
remain canonical; this model is a rebuildable index over them.

Keep the serialized form backend-neutral. Local storage, S3/MinIO, and tests
should be able to use the same logical model with different persistence
adapters.

Build incrementally after receive-pack:

- parse newly uploaded packs;
- identify new commits, trees, tags, and blobs;
- update commit graph and ref projections only for affected refs;
- reuse existing tree and path indexes for unchanged parent commits;
- mark the projection version and rebuild status atomically.

Support full rebuild from storage:

- scan refs and known pack/object storage;
- parse reachable commits and trees;
- reconstruct path indexes and snapshots;
- verify that derived ids match the canonical Git object ids;
- replace the old projection only after the rebuild completes.

Prefer append-friendly records for history and immutable commit/tree facts.
Mutable state should be limited to refs, projection version markers, and
compaction metadata.

## Fast Reads

Support efficient read paths:

- resolve a ref to a commit without opening the whole repository;
- load a file or a small set of files at a commit using path indexes;
- list tree entries for a directory without materializing the full tree;
- get recent commits for a branch from the commit graph index;
- answer ancestry and reachability checks needed by fetch and ref updates;
- materialize a full snapshot only when a caller explicitly needs it.

The implementation should avoid loading complete packs or complete repository
history for narrow reads.

## Verification

Cover at least these cases:

- build the model from a simple linear history and load files at each commit;
- update the model after a fast-forward push without rebuilding unchanged
  parents;
- update the model after branch creation, branch deletion, tag creation, and
  annotated tag peeling;
- rebuild the model from canonical objects and get identical projection records;
- detect and reject projection records that disagree with Git object ids;
- compare model-backed reads with JGit-backed reads for the same fixtures;
- read a small set of files from a large tree without materializing unrelated
  paths;
- recover cleanly when a rebuild fails before publishing the new projection.

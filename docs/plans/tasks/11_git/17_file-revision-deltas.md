# Delta Encoding for File Revision Updates

## Requirements

Reduce outgoing pack size for changes made through `GitFileAccess` by comparing
the previous and new contents of changed files. Keep the current streaming
whole-object implementation as the correctness baseline. This is future work;
delta generation is not required for the initial file-access implementation.

## Design

- Reuse the existing native delta and pack machinery through repository APIs;
  do not add local- or S3-specific behavior to file access.
- Choose the previous revision's blob for the same path as the first candidate
  base. Bound memory and computation; use a whole object when a delta is not
  beneficial or a suitable base is unavailable.
- Emit `OFS_DELTA` only when its base precedes it in the same outgoing pack.
  Include the cost of sending that base when deciding whether the delta saves
  bytes. A receiver already having the old revision does not make an external
  object addressable by an OFS offset.
- If sending only a delta against a receiver-owned external base is needed,
  handle that as negotiated thin-pack `REF_DELTA` behavior; do not encode an
  external base as `OFS_DELTA`.
- Preserve object IDs, pack counts and checksums, captured ref expectations,
  publication policy, and discard behavior. Do not buffer whole packs in memory.

## Implementation Plan

1. Trace the completed file-access writer and existing delta encoder; establish
   representative configuration-file fixtures and whole-object pack sizes.
2. Compare old and new blobs for changed paths with bounded resource usage.
3. Select beneficial deltas, order in-pack bases before their dependents, and
   write correct offsets through the native pack writer.
4. Verify reconstruction, wire interoperability, resource bounds, and unchanged
   conflict/discard behavior. Measure transmitted bytes including bases.

## Dependencies

The streaming `GitFileAccess` implementation and its whole-object tests must be
complete first. Coordinate with [pack reuse](10_pack-reuse-and-multipack-index.md)
without duplicating its existing-entry reuse scope; this task generates deltas
between file revisions.

## Acceptance Criteria

- A beneficial fixture produces a smaller complete outgoing pack than the
  whole-object baseline, including the cost of any transmitted base.
- Native Git and an independent Git implementation reconstruct the exact new
  blob and commit from an emitted OFS delta pack.
- Missing bases, new files, empty files, and unhelpful deltas use whole objects.
- No OFS entry points outside the outgoing pack or forward to its base.
- Large-file processing respects explicit resource bounds without retaining
  the entire pack in memory.
- Concurrent ref changes still fail publication, and discard publishes no refs.

# Verify Receive-Pack Ref Publication Ordering

Status: todo
Source: narrowed from the former atomic publication boundaries task.

Ensure receive-pack makes new refs visible only after the incoming pack has
been fully ingested, validated, and made readable from the published object
stores. A multi-ref update requested with `atomic` must become visible as one
logical ref snapshot: either every eligible ref update is applied or none is.

## Scope

- Keep pack, index, and manifest publication independent from ref atomicity.
- Preserve the ordering `pack ingestion and validation -> object publication ->
  ref publication`.
- Cover mixed stale and valid commands for non-atomic pushes and all-or-nothing
  behavior for atomic pushes.
- Verify that refs never expose an incomplete object closure after publication.

## Out of Scope

- Making pack, index, or manifest publication atomically coupled to refs.
- Filesystem crash recovery across multiple loose-ref files.
- Garbage-collecting orphaned packs or quarantined objects.

## Acceptance

- A ref is not published when pack ingestion, object validation, or closure
  checks fail.
- Non-atomic commands are applied independently after successful ingestion.
- Atomic commands expose either the complete requested ref set or the prior
  ref set.
- Tests document and enforce the publication order and failure behavior.

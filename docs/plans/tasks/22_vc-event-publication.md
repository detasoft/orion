# Publish VC changes outside the resource write lock

## Requirements

Allow a change-event publisher to read the committed resource from another thread.
Preserve commit serialization, snapshot consistency, and revision conflict checks.

## Design decisions

`VcOperation.commit` currently holds the resource write lock while backend commits
can publish events. A publisher that waits for another thread to open the same
resource blocks that read. Define the commit/publication boundary in the common
contract before changing backends; do not duplicate lock policy in implementations.

## Implementation plan

1. Define when a committed revision becomes readable and when publication occurs.
2. Apply that boundary to VC operations and Git-backed BlobStorage.
3. Re-enable `GitBlobStorageTest.allowsReadingMaterialWhileARefChangeIsBeingPublished`.
4. Check successful publication, conflicts, and failed commits without partial state.

## Dependencies

The current VC operation and consumer migration must be complete.

## Acceptance criteria

The retained disabled test passes, publication cannot deadlock a resource read,
and snapshot/conflict tests continue to pass without weakening commit atomicity.

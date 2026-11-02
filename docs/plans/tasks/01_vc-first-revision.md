# Create the first version of a VC resource

## Requirements

Restore explicit creation of the first durable revision for an absent resource.
Keep the existing snapshot operation contract for stored revisions. Preserve
first-start configuration and key-material scenarios rather than replacing them
with fixtures that already contain the requested data.

## Design decisions

- `VcResource.open` currently opens a stored snapshot; an absent resource has no
  `VcRevision.Stored` to supply to that operation.
- Define the creation contract in the VC abstraction before implementing it in
  BlobStorage backends and their configuration/key-material consumers.
- Do not fabricate a stored revision or treat `Latest` as an implicit permission
  to overwrite a concurrently created resource.
- Git object creation and branch publication remain separate operations.
- Restore or explicitly resolve the retained first-branch tests for initializing
  a symbolic HEAD; preserve detached HEAD behavior.

## Implementation plan

1. Define explicit first-version creation and its concurrency/error semantics.
2. Implement the agreed contract in the affected BlobStorage backends.
3. Restore initial configuration and key-material publication through that path.
4. Re-enable tests marked `@Disabled` with this task path and verify their original
   behavior, including concurrent creation, failure cleanup, and restart.

## Dependencies

The current VC snapshot-operation and consumer migration must be complete.

## Acceptance criteria

- An absent resource can acquire a real stored revision without a fabricated base.
- Concurrent creators cannot silently overwrite one another.
- Failed creation does not publish partial content or a successful-change event.
- Configuration/key-material startup and reopening preserve durable contents.
- All tests disabled for this task are restored and pass; existing-version tests
  continue to pass.

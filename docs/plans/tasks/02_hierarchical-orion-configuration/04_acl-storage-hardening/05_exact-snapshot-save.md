# Enforce the Saved Snapshot Contract

Status: todo

Make `AccessControlStorage.save(snapshot, request)` persist exactly the
configured ACL file set with a documented publication guarantee.

## Scope

- Resolve configured paths once into an immutable, normalized, ordered, unique
  set that includes the primary ACL file.
- Reject a snapshot with missing, extra, duplicate, aliased, absolute, or
  escaping keys before performing any write.
- Ensure omitted files cannot retain stale ACL data and extra files cannot be
  persisted while remaining invisible to `load`.
- Define whether a multi-file Local save is atomic. If `snapshot` promises one
  revision, publish all files as one generation; otherwise narrow the API name
  and document the weaker observable behavior explicitly.
- Preserve native Git save audit metadata and use its single-commit boundary
  as the atomic publication point.

## Completion Criteria

- Tests cover exact-set success, missing and extra keys, normalized aliases,
  multiple files, overwrite, and a failure during Local publication.
- A successful load after save observes precisely the accepted snapshot file
  set.
- Callers cannot accidentally perform a partial ACL update through this API.

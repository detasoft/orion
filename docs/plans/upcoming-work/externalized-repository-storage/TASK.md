# Externalize Repository Storage

Status: todo

Move repository contents behind a storage abstraction so production deployments
are not required to keep Git objects, refs, packs, and indexes directly in the
local filesystem.

## Scope

- Define the repository storage boundary for objects, refs, pack files, indexes,
  temporary writes, and atomic publication.
- Keep the existing file-backed storage as one implementation of that boundary.
- Add at least one external storage implementation target after the boundary is
  explicit.
- Preserve native repository backend behavior, pack-index reads, delta
  reconstruction, pack building, and receive-pack publication semantics.
- Define consistency, locking, compare-and-swap, cleanup, and recovery behavior
  for non-filesystem storage.
- Coordinate with
  `../../current-work/production-native-git-repository-backends/TASK.md`,
  especially atomic publication boundaries.

## Child Tasks

- [ ] Extract storage interfaces for repository contents and ref state.
- [ ] Adapt the file-backed repository provider to the new storage boundary.
- [ ] Define atomic publication and recovery semantics for external storage.
- [ ] Implement an external storage backend candidate.
- [ ] Add parity tests that run repository backend behavior against file-backed
  and externalized storage.

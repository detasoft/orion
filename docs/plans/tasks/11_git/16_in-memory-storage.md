# Implement Independent In-Memory Git Storage

Status: active

- Owner: codex, session 01a0ecdb-205e-7522-87dd-096112e78767,
  branch `codex/git-memory-storage-01a0ecdb`,
  worktree `.worktrees/git-memory-storage-01a0ecdb`, started 2026-09-29 13:45 Europe/Amsterdam.

## Requirements and accepted design

The user explicitly selected an independent `InMemoryStorage` implementation of
`GitStorageApi` in `storage.memory`: pack bytes, indexes, and refs in memory,
without files or H2. This is not a wrapper around local storage or its existing
H2-backed memory mode. Shared Git parsing and resolution algorithms remain shared.

- Provide `pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage` and the
  memory pack/upload-index implementations required by the existing interfaces.
- Use ordinary in-memory data structures and bounded byte blocks with long
  offsets; do not impose a new 2 GiB pack limit or require one contiguous buffer.
- Preserve object/pack validation, identity invalidation after byte changes,
  delta resolution, publication visibility, duplicate publication, and cleanup.
- Preserve ref ordering in results, expected-old CAS, atomic batch updates and
  snapshots, missing-object checks, HEAD validation, and repository isolation.
- Keep resource ownership explicit: failed attempts must not damage published
  packs; closing storage releases retained resources, and callbacks must not
  hold global locks across arbitrary client code.
- Migrate `InMemoryNativeGitRepositoryProvider` and every real in-repository
  memory-storage consumer to the new implementation. Remove the replaced H2
  memory-storage path and no-argument `LocalGitStorage` constructor, together
  with obsolete memory-only local helper paths. Keep one memory backend.
- Preserve local on-disk behavior and persisted formats. Existing meaningful
  memory/disk interoperability coverage must continue through supported APIs;
  do not simply delete tests made inconvenient by the replacement.
- Reuse existing pack parsing, object reading, and delta algorithms. Extract
  only the smallest neutral shared helpers justified by actual use in both
  implementations; do not duplicate whole local algorithms or add speculative
  frameworks, registries, configuration, or Maven modules.

## Dependencies and scope

The interfaces are available on main in commit
`33a75bfc585fae9a2835e70cef9fde0d7875bbb0` (reviewed source commit `659a0891`).
That result passed isolated and post-integration full verification, and its
temporary branch and worktree have been removed.

No S3 backend, network service, persistence migration, broad storage architecture
redesign, or unrelated module-review finding repair is included.

## Implementation plan

1. Trace storage/pack contracts and the local memory mode, constructors, provider,
   direct pack factories, and tests. Establish exact lifecycle and failure
   contracts before replacing the memory implementation.
2. Implement memory storage, indexed pack, and temporary dependency state with
   ordinary collections/byte blocks. Share neutral byte/validation logic only
   where needed by the two real implementations.
3. Move every memory consumer to the new backend and remove the old memory
   path. Keep local filesystem creation and publication on the disk backend.
4. Run existing behavior coverage against the real memory backend, including
   straightforward publish/read/ref operations, delta/external-base processing,
   duplicate and failed publication cleanup, long offsets, stale concurrent
   ref CAS, and all-or-nothing atomic snapshots. Test instance isolation and
   resource release without source-text or reflection architecture assertions.
5. Run focused Git tests with `make run-test MODULE=<module> TEST='<locator>'`
   and the complete `make test` suite. Inspect real production dependencies
   to confirm the memory path does not use H2, local storage, or files.

## Acceptance

- `InMemoryStorage` is a real alternative implementation, independent of H2 and
  filesystem/local storage classes, used by the memory repository provider.
- Old H2 memory repository and pack paths have been replaced in all consumers.
- Existing disk behavior and meaningful memory behavior coverage are preserved.
- Required focused/full verification passes; lifecycle, atomicity, concurrency,
  and non-trivial delta/failure scenarios have behavioral evidence.

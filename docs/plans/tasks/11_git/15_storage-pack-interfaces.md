# Extract Git Storage and Pack Interfaces

Status: active

- Owner: codex, session 01a0ecdb-205e-7522-87dd-096112e78767,
  branch `codex/git-storage-interfaces-01a0ecdb`,
  worktree `.worktrees/git-storage-interfaces-01a0ecdb`, paused 2026-09-29 13:35 Europe/Amsterdam;
  next: await user authorization to integrate reviewed commit
  `659a08911a98718d14b8eb060500043feef48396`, then verify integration and clean up.

## Requirements and accepted design

The user approved extracting both `GitStorageApi` and `IndexedPack` as interfaces
to prepare alternative Git storage. This is a bounded extraction of existing
contracts, independently authorized ahead of the broader external-storage work.

- Keep `GitStorageApi` as the storage-neutral repository storage contract.
- Turn `IndexedPack` into a contract containing the pack operations required by
  real consumers: byte access, object index operations, pack identity and
  validation, and resource lifecycle. Do not expose H2 or filesystem paths.
- Move the current implementations into
  `pro.deta.orion.git.parser.v2.storage.local`, using `LocalGitStorage` and a
  concrete local indexed-pack implementation. Keep implementation-only helpers
  private or package-private where possible.
- Keep filesystem creation, open, directory, and copy operations on the local
  implementation. Storage-neutral consumers obtain working packs through
  `GitStorageApi.newPack()` and publish through `persist()`.
- Preserve both current disk and memory operation, persisted formats, Git wire
  behavior, streaming, pack validation, ref CAS/atomicity, publication visibility,
  locking, failure propagation, and resource ownership.
- Update all production and test consumers in the repository. Remove the old
  concrete API paths without compatibility aliases or duplicate implementations.
- Shared metadata values belong to the contract and must not depend on local
  implementation types. Avoid additional public types unless needed by callers.

## Scope and dependencies

No prerequisite implementation is needed for this behavior-preserving extraction.
The user-approved boundary is limited to the current API and pack operations;
it does not settle external backend consistency or publication design.
The broader architecture review and external storage task remain outstanding.
No S3 backend, new Maven module, provider registry, configuration switch,
persistence migration, or unrelated parser/transport restructuring is included.

## Implementation plan

1. Trace `GitStorageApi`, `IndexedPack`, `PackObjectLocation`, pack builders,
   parsers and resolvers, native repository construction, and their actual
   consumers. Read applicable class rules and existing behavior tests.
2. Extract the smallest contracts in the existing API packages and move concrete
   storage, indexed-pack, and implementation-specific helpers to `storage.local`.
   Retain existing algorithms; separate path-specific methods from pack methods.
3. Update native storage composition and all in-repository constructors, static
   pack factories, imports, and consumers to use the correct contract or local
   composition point. Do not introduce local downcasts in neutral consumers.
4. Preserve existing behavioral tests through the contracts. Add meaningful
   coverage proving an independently implemented pack can pass through relevant
   neutral processing without a dependency on the local concrete class. Cover a
   straightforward pack flow and a non-trivial delta/publication or cleanup case.
5. Run focused Git tests with `make run-test MODULE=<module> TEST='<locator>'`
   and the complete `make test` suite. Inspect all consumer updates and verify
   that local implementation details do not leak through the new interfaces.

## Acceptance

- Both interfaces can be implemented without filesystem paths or H2 types in
  their contracts, and production algorithms use their required pack operations.
- Local storage remains the working implementation with unchanged formats and
  behavior; both disk and memory coverage pass.
- Pack and storage resource ownership and publication semantics remain explicit
  and tested, including meaningful failure or delta behavior.
- All consumers compile and required focused/full verification passes.

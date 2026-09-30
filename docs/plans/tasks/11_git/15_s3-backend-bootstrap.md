# Bootstrap the S3 Git Backend

- Owner: codex, session 01a0f177-8697-7993-a635-8992bcdf75cf, branch `codex/s3-backend-01a0f177`,
  worktree `.worktrees/s3-backend-01a0f177`, paused 2026-09-30 11:21 Europe/Amsterdam;
  next: authorize integration of reviewed commit `57715a8694b1bea05ebe4a729022dc4147a846b7`,
  then verify the integrated result and complete cleanup.

## Required result

Deliver the user-approved first S3 backend stage in a new `git/git-s3-storage`
module: real repository metadata create/find/exists/list, S3 configuration,
and two explicit stubs implementing the existing `GitIndexApi` and
`GitStorageApi`. Prove the implemented behavior against MinIO.

## Dependencies and scope

Use the existing committed native repository and storage interfaces. This
bounded metadata/bootstrap stage is independent of the pending storage
architecture review: it neither extracts an external storage contract nor
implements Git object/ref publication semantics. The broader work in
`06_externalized-repository-storage.md` and its review dependency remain pending.
Do not implement refs, pack storage, push/fetch, load/save files, migrations,
maintenance, locks, or local fallback. Full stateless server operation remains
outside this stage because bootstrap Git reads require the deferred APIs.

## Current model and design

`NativeGitRepositoryProvider` owns repository discovery and creation;
`NativeGitRepository` composes `GitIndexApi` and `GitStorageApi`.
`BootstrapContext` currently chooses the file provider. Reuse these contracts
without adding another repository abstraction. Keep file-backed behavior and
the default configuration unchanged.

Keep the S3 implementation in the new module, with only necessary Maven,
configuration, composition, and test-fixture wiring elsewhere. Reuse existing
AWS SDK dependencies and S3 location/credential conventions where suitable.
Support a bucket, prefix, region, endpoint, and test-compatible credentials
without logging secrets. Make S3 explicitly selectable; validate configuration
and provide a clear failure for unsupported Git operations.

Persist minimal repository metadata under safe deterministic repository keys.
Use conditional creation so competing providers cannot overwrite an existing
repository. Discovery and opening must read S3, with no local durable state or
authoritative process cache. Preserve canonical repository-name validation and
the default HEAD target. Do not initialize refs through a fake index.

S3 index and pack-storage implementations remain explicit stubs. Operations
must report unsupported behavior rather than fake success or empty repository
contents. Define resource ownership and close semantics; metadata access must
continue to work across independent provider instances.

## Implementation plan

1. Inspect the existing native provider, configuration binding, S3 resolvers,
   lifecycle, and MinIO fixture; present the reused contracts and new public
   surface before implementing them.
2. Add the new Maven module and the two stubs, using existing API contracts.
3. Add minimal S3 metadata operations with conditional create, validated keys,
   bounded transport behavior, correct missing/error distinction, and explicit
   resource ownership.
4. Add configuration and composition wiring for explicit S3 selection, with a
   usable configuration example documenting this stage's limitations.
5. Add observable-behavior tests and actual MinIO tests using the existing
   `MinioS3TestServer` fixture where practical. Keep tests in the new module
   when possible; avoid production dependencies on test support.

## Acceptance and verification

- Build the new module within the reactor and run `make test`.
- Run focused tests through `make test MODULE=... TEST=...` as appropriate.
- Run actual MinIO-backed tests, with the documented integration goal or a
  focused verify invocation if no Make goal supports the necessary selection.
- Verify metadata create/find/exists/list, missing repositories, duplicate and
  concurrent creation, reopening through an independent provider, repository
  and prefix isolation, invalid names/configuration, and storage failures.
- Verify configuration selects S3 and preserves the existing file default.
- Verify stub operations clearly fail and resource closure is safe.
- Report exact tested boundaries. Do not claim push/fetch or full server
  startup works with the intentionally incomplete APIs.

## Preserved behavior and limitations

Preserve existing file and memory backends, authorization above the repository
provider, canonical names, and existing API semantics. Parallel changes to Git
APIs in the primary worktree are unrelated and must not be copied or altered.
This stage establishes S3 metadata persistence and wiring only; the Git data
plane will be implemented later.

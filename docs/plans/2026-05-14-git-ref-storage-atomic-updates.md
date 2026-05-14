# Git Ref Storage and Atomic Updates

## Goal

Add a JGit-free ref storage layer for Orion repositories, including ref reads,
ref listing, symbolic `HEAD`, compare-and-set ref updates, deletes, and the
minimum metadata needed for native upload-pack and receive-pack flows.

Git objects and packs are immutable, but refs are mutable repository state. This
plan defines how Orion updates that mutable state safely without delegating to
JGit `RefDatabase` or `RefUpdate`.

## Current State

`JGitRepository` updates refs through JGit `RefUpdate` and translates JGit
receive commands into Orion `GitRefUpdate` events.

The S3 repository prototype has `S3RefDatabase`, but most required operations are
not implemented: `newUpdate`, `newRename`, `exactRef`, additional refs, peeling,
and symbolic refs are unsupported or incomplete.

The pack storage, pack index, object model, and delta reconstruction plans define
how repository objects become available. They do not define how branches, tags,
and `HEAD` are persisted or updated.

The native remote single-file push plan needs compare-and-set semantics for
remote refs. Orion's own repository storage needs the same semantics locally for
receive-pack ingestion and internal file saves.

## Non-Goals

Do not implement Git object storage in this layer.

Do not implement upload-pack or receive-pack protocol parsing here. This layer
provides ref state operations that protocol code can call.

Do not implement full reflog compatibility in the first phase. Define the event
and storage shape, then add durable reflogs after safe ref updates exist.

Do not implement packed-refs compaction first. Start with logical ref records and
add a packed-refs compatible representation later if useful.

Do not allow force updates by default. Force behavior must be an explicit update
policy decided by the caller.

Do not depend on JGit in production code. Tests may compare behavior against JGit
or Git CLI fixtures.

## Ref Model

Introduce ref value objects:

- `GitRefName`: validated full ref name;
- `GitRefTarget`: object id target or symbolic target;
- `GitRef`: name, target, peeled target if known, storage kind, update version;
- `GitRefUpdateRequest`: ref name, expected old target, new target, update policy,
  actor, message;
- `GitRefUpdateResult`: status, old target, new target, conflict details;
- `GitRefStoreSnapshot`: visible refs plus `HEAD` at one read version.

Support these ref namespaces first:

- `HEAD`;
- `refs/heads/*`;
- `refs/tags/*`;
- `refs/remotes/*` only if a caller explicitly needs remote-tracking refs;
- future internal refs under an Orion-owned namespace.

Use full ref names internally. Short branch names are caller-facing conveniences
and should be normalized before they reach the ref store.

## Ref Validation

Add Git-compatible ref name validation:

- no empty ref names;
- no path traversal;
- no `..` component;
- no ASCII control characters;
- no spaces where Git forbids them;
- no `~`, `^`, `:`, `?`, `*`, `[`, or backslash;
- no trailing slash;
- no `.lock` suffix;
- no component starting with `.` unless explicitly allowed by Git rules;
- no single-level branch ref unless the caller expands it to `refs/heads/<name>`;
- `HEAD` allowed only as the special symbolic ref name.

Validation should return typed errors that include the invalid ref name and rule,
without throwing generic parsing exceptions at API boundaries.

## Storage API

Define a backend-neutral store:

```text
readRef(name) -> Optional<GitRef>
readRefs(prefixes) -> GitRefStoreSnapshot
readHead() -> GitRef
updateRef(request) -> GitRefUpdateResult
deleteRef(request) -> GitRefUpdateResult
```

Update requests should support policies:

- create only;
- update only;
- delete only;
- fast-forward only;
- compare-and-set exact old id;
- force, explicit only;
- no-op allowed or rejected.

The first implementation can require callers to decide fast-forward validity
using commit graph data. This store should enforce expected old target and policy
flags, then return typed results.

## Atomic Updates

Ref updates must be compare-and-set operations.

For a single ref update:

1. read current target and update version;
2. verify expected old target;
3. verify update policy;
4. verify new object exists when required;
5. write new target with a conditional write;
6. publish ref update event metadata after the write succeeds.

For multiple refs, add a later transaction API:

```text
updateRefs(List<GitRefUpdateRequest>) -> GitRefTransactionResult
```

Multi-ref transactions should be all-or-nothing for backends that can support it.
If a backend cannot provide atomic multi-ref updates, the API must report that
limitation rather than partially applying updates silently.

## Local File Backend

Start with a local file backend for deterministic development tests:

- one file per loose ref;
- `HEAD` as a symbolic ref file;
- atomic write through temporary file and rename;
- lock file or filesystem-level CAS to avoid concurrent writers;
- fsync policy documented and configurable;
- stale lock detection with a conservative timeout;
- no packed-refs in the first phase.

Use the same logical ref API as S3 and future backends. Do not expose local file
paths to callers outside diagnostics.

## S3-Compatible Backend

Add an S3-compatible backend after the API and local backend are stable:

- one object per ref;
- metadata with update version, object id, target kind, and last update time;
- conditional put using ETag/version preconditions where available;
- create-if-absent for branch creation;
- delete-if-match for ref deletion;
- list by prefix for advertisement and fetch negotiation;
- optional sidecar records for reflog entries.

S3 consistency behavior and conditional-write support must be part of the backend
contract. If a provider cannot support safe CAS, it should not be used for
mutable refs without an external lock service.

## Symbolic Refs and HEAD

Support symbolic `HEAD` from the first implementation.

Required behavior:

- initialize `HEAD` to `refs/heads/<defaultBranch>`;
- read `HEAD` as symbolic and as resolved object id when the target exists;
- update `HEAD` symbolic target only through a dedicated operation;
- reject symbolic refs outside `HEAD` unless a future use case requires them;
- advertise `HEAD` symref metadata for Git clients where applicable.

When the target branch does not exist, `HEAD` should still be readable as an
unborn branch.

## Tags and Peeling

Refs under `refs/tags/*` may target commits or annotated tag objects.

The ref store can persist a peeled target as optional metadata after object
parsing resolves it. Peeling should be rebuildable from object storage and should
not be treated as canonical source of truth.

If peeled metadata is stale or missing, callers should be able to request
on-demand peeling through the object model.

## Reflog and Events

Define a ref update event record:

- repository;
- ref name;
- old target;
- new target;
- update type;
- result;
- actor;
- message;
- timestamp;
- request id.

In the first phase, publish Orion events after successful updates. Durable reflog
storage can follow:

- append-only local file or object records;
- bounded retention;
- rebuild behavior when reflog is missing;
- no dependency on reflog for canonical ref state.

## Integration With Native Git

Consumers:

- native receive-pack uses `updateRef` after pack validation and object
  availability checks;
- native upload-pack uses `readRefs` and `readHead` for advertisement and
  negotiation;
- native remote single-file push uses the same update semantics conceptually for
  expected-old-id behavior;
- commit information model consumes ref update events to update projections;
- mirror sync uses ref update events and refspec policies.

Ref update order matters: objects must be validated and visible before refs point
to them. If object publication fails, refs must remain unchanged.

## Phased Plan

Phase 1: Ref model and validation.

Add ref name, target, update request, update result, and validation rules. Cover
valid branches, tags, `HEAD`, invalid names, and short-name normalization.

Phase 2: In-memory ref store.

Implement a deterministic in-memory store for tests with compare-and-set updates,
deletes, `HEAD`, and prefix listing.

Phase 3: Local file ref store.

Persist loose refs and symbolic `HEAD` using atomic local writes, lock handling,
and reload from disk.

Phase 4: Update policies.

Implement create-only, update-only, delete-only, exact expected old id, no-op,
and explicit force policies. Add typed conflict results.

Phase 5: Ref advertisement support.

Expose read snapshots suitable for upload-pack advertisement, including `HEAD`
symref metadata and optional peeled tag data.

Phase 6: Object existence and fast-forward checks.

Integrate with object lookup and commit graph services so callers can require
existing new targets and fast-forward-only branch updates.

Phase 7: S3-compatible ref store.

Implement conditional object writes, prefix listing, delete-if-match, and provider
capability checks for CAS safety.

Phase 8: Ref update events and reflog shape.

Publish ref update events from successful writes. Add a durable reflog sidecar
only after event behavior is stable.

Phase 9: Native receive-pack integration.

Use the ref store after pack ingestion: validate objects, apply commands with
expected old ids, publish receive events, and reject partial failures.

Phase 10: Multi-ref transactions.

Add all-or-nothing multi-ref updates for receive-pack atomic pushes and mirror
sync use cases where backends can support them.

## Open Questions

Should the first production backend be local files only, with S3 refs gated until
conditional write behavior is proven against MinIO and AWS S3?

Should fast-forward checks live inside the ref store or in a higher-level
repository service that has commit graph access?

How much reflog compatibility is needed for Git clients versus Orion's own audit
events?

Should `refs/remotes/*` be supported in Orion repositories, or should remote
mirror state live outside canonical repository refs?

What default branch should initialize `HEAD`: `master`, `main`, or a configured
repository default?

How should stale local lock files be detected without risking two active writers?

## Verification

Cover at least these cases:

- valid branch, tag, and `HEAD` names pass validation;
- invalid ref names fail with typed rule errors;
- in-memory store creates, updates, deletes, and lists refs by prefix;
- create-only rejects an existing ref;
- update-only rejects a missing ref;
- exact expected old id rejects stale updates;
- no-op update behavior follows request policy;
- explicit force is required for non-fast-forward updates when policy checks are
  enabled;
- deleting a missing ref returns the configured typed result;
- symbolic `HEAD` resolves when target exists and remains readable when unborn;
- local file backend reloads refs after restart;
- interrupted local write leaves old or new valid ref, not partial content;
- concurrent local updates allow only one matching CAS write;
- S3 backend conditional writes reject stale ETags or versions;
- listing refs for advertisement includes branches and tags, not invalid internal
  files;
- peeled tag metadata is optional and rebuildable;
- ref update event includes old id, new id, update type, actor, and result;
- receive-pack integration never updates refs before objects are visible;
- multi-ref transaction either applies all refs or none when enabled;
- production ref store has no JGit dependency.

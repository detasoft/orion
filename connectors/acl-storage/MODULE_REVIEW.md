# Module Review: `connectors/acl-storage`

Date: 2026-09-03
Status: reviewed in isolation

## Scope and coverage

This review covers the module POM, all four production classes, the current
storage test suite, the remaining test helper, and module-specific history
including the removal of former Git adapters and the restoration of native Git
storage.

The review treats imported types as contracts visible through their use in
this module. It deliberately does not inspect the implementation of
`core/acl`, the configuration schema, native Git storage, bootstrap wiring, or
external callers. Those boundaries may change the recommended migration and
are called out as open questions rather than guessed.

This was a static architecture review. Maven verification was not run for the
review itself because the repository review rules assign verification to
implementation work.

## Current conceptual model

`AccessControlStorageResolver` reads one bootstrap location and selects a
concrete storage directly:

| Location | Implementation | Durable unit | Version | Change notification |
| --- | --- | --- | --- | --- |
| empty or `file:` | `LocalAccessControlStorage` | separate filesystem files | absent | default no-op |
| `local:` | `NativeGitAccessControlStorage` | files on one Git ref | repository version | filtered ref updates |

Both implementations present the same apparent operations: load a map of
configured paths, save a snapshot with author/message metadata, identify one
primary path, and optionally observe changes. Their actual guarantees differ.

Local storage resolves each configured logical path below a configured
directory and reads or writes files sequentially. Native Git freezes its
repository, ref, and path coordinates, delegates one map operation to a
repository, returns its version, and forwards accepted updates for the
selected ref.

There is no module-owned transaction, lock, generation manifest, or
compare-and-set boundary. The selected backend owns durable mutation, while
Local storage continues to read its addressing from the mutable configuration
object.

## Highest-value findings

### 1. One storage type promises capabilities that only one backend implements

**Finding.** The two concrete classes are presented as substitutes but expose
materially different storage models. Native Git supplies a version, uses save
author/message metadata, and emits ref-change notifications. Local storage
returns no version, ignores the save request, and inherits a no-op change
subscription.

**Evidence.** `NativeGitAccessControlStorage.load` returns the repository
version at lines 38-46, `save` translates the author and message at lines
51-61, and `onChange` filters ref updates at lines 70-78. Local returns
`Optional.empty()` at `LocalAccessControlStorage.java:32` and ignores its
`request` parameter at lines 41-49. No current contract test demonstrates
interchangeable behavior across both implementations.

**Why it likely exists.** Storage schemes were added over time beneath an
existing broad interface. Git-specific revision and notification features
were retained as optional-looking properties instead of deciding which
guarantees every authoritative ACL store must provide.

**Simpler model.** First choose one mandatory contract. If live mutation,
audited revisions, and reload notification are required, expose native Git as
the authoritative store and make filesystem content an explicit bootstrap
import. If the real requirement is only loading and saving bytes, remove
version, audit metadata, and change observation from the common contract.
Introduce capability types only after a real caller requires both modes at
runtime.

**Contract change.** The two locations would no longer be freely
interchangeable. Either filesystem storage loses writable-live-store status,
or callers lose the assumption that every store can provide revision and
notification semantics.

**Consequences.** Callers stop branching on empty versions or silently missing
notifications. A configured mode either supplies its declared guarantees or
fails during resolution.

**Confidence.** High that the implementations are not substitutes; medium on
which smaller contract is correct because external consumers were excluded.

### 2. `save(snapshot)` neither preserves the configured file set nor publishes one snapshot

**Finding.** `load` and `save` use different definitions of the stored ACL.
Loading reads exactly `config.getPaths()`, while saving iterates whatever keys
the supplied snapshot contains. Missing configured keys retain stale data,
extra keys are persisted but never loaded, and normalized aliases can refer to
the same physical file. Local storage can also expose a partially written
multi-file state if a later write fails or a concurrent load occurs.

**Evidence.** Local loads configured paths at
`LocalAccessControlStorage.java:25-31` but saves `snapshot.files()` at lines
43-49. It neither removes configured files absent from the snapshot nor checks
that the snapshot key set equals the configured key set. Its writes are
independent and have no publication marker or rollback. Native Git also passes
`snapshot.files()` directly at `NativeGitAccessControlStorage.java:55-59`;
this isolated review does not prove how omitted configured paths affect the
repository tree.

Local normalization can make two logical snapshot keys refer to one physical
file. For example, `a/b` and `a/./b` remain distinct map keys but normalize to
the same target.

**Why it likely exists.** The API moved from one ACL file to a map of files,
but per-file write behavior was retained and no exact-set or publication
invariant was added.

**Simpler model.** Make configured paths an immutable, normalized, unique set
owned by the storage instance. Require every saved snapshot to contain exactly
that set. Then define whether “snapshot” means one atomic revision. If it does,
publish an immutable filesystem generation through one switch rather than
simulating a snapshot with sequential overwrites. If it does not, narrow the
API name and document the weaker behavior.

**Contract change.** Exact-set validation rejects partial and extra-file saves
that currently succeed. Atomic filesystem publication changes the durable
layout; explicitly weakening the contract permits mixed revisions and must be
accepted by ACL consumers.

**Consequences.** One path set becomes authoritative, stale-file and alias
cases disappear, and failure behavior becomes testable. Atomic generations
cost additional layout logic; the weaker alternative is smaller but allows
transiently inconsistent authorization data.

**Confidence.** High on the key-set and partial-write behavior; medium on the
required atomicity because no consumer was inspected.

### 3. Backend instances disagree about configuration lifetime

**Finding.** Native Git copies its addressing fields at construction, while
Local storage retains and repeatedly reads the mutable schema object.

**Evidence.** `NativeGitAccessControlStorage` assigns repository, ref, and
`List.copyOf(paths)` in its constructor at lines 27-33. Local stores the whole
configuration object at line 19 and consults its paths, primary path, and
location during later operations at lines 25, 57, and 70.

Mutating one `BootstrapAccessControlConfig` after resolution therefore changes
Local behavior immediately but does not alter an existing native Git storage
instance.

**Why it likely exists.** Constructors cached only values needed for setup,
while the schema object remained a convenient source for the rest. There is no
explicit decision whether configuration objects are immutable snapshots or
live reload handles.

**Simpler model.** Resolve and validate location, normalized path set, primary
path, and ref once. Store only those immutable values in each backend. Runtime
reconfiguration should replace a complete storage instance rather than mutate
half of its coordinates in place.

**Contract change.** Mutating `BootstrapAccessControlConfig` after resolution
would no longer alter an existing storage. A reload owner would have to create
and swap a new instance.

**Consequences.** Every operation uses one coherent configuration generation,
constructors become the validation boundary, and concurrency no longer
depends on undocumented schema mutation.

**Confidence.** High.

### 4. Storage operation failures use incompatible control-flow models

**Finding.** Reads return typed `Result` failures, while writes communicate
expected backend failures through unrelated unchecked exceptions. Equivalent
validation and backend failures are also translated differently by Local and
native Git storage.

**Evidence.** Local converts `IOException` and `IllegalArgumentException` to
`GENERAL` on load at `LocalAccessControlStorage.java:33-36`, but save wraps only
`IOException`, allowing path validation to escape directly. Native Git
converts selected exceptions on load and throws `IllegalStateException` on
save.

**Why it likely exists.** The read side needed `NOT_FOUND` for bootstrap
creation, while save remained a void command. Each connector then translated
its native failures independently.

**Simpler model.** Use one small failure vocabulary for both operations:
`NOT_FOUND`, invalid configuration or snapshot, unavailable backend, conflict
if versioned writes need it, and unexpected failure. Represent expected save
failure through the same typed result path as load, or through one storage
exception type if callers do not compose results. Do not catch arbitrary
runtime exceptions as backend failures.

**Contract change.** `save` would no longer appear to succeed and then throw an
arbitrary runtime type. Callers must handle one explicit save outcome.

**Consequences.** Retry and user-facing diagnostics can be decided once rather
than per backend. This changes the imported storage API and requires consumer
migration outside this isolated module.

**Confidence.** High on the inconsistency; medium on result versus exception as
the final representation.

## Smaller inconsistencies

- Native Git validates `..` and absolute forms before converting backslashes
  to slashes (`NativeGitAccessControlStorage.java:102-111`). A percent-decoded
  backslash can therefore become a forbidden segment after validation. The
  current file provider hashes repository names, so this is an inconsistent
  repository-name boundary rather than a demonstrated filesystem escape.
- Local path containment is lexical. A symlink below the configured directory
  can redirect `Files.readAllBytes` or `Files.write` outside it. If containment
  is a security boundary, verify physical paths and use no-follow operations
  rather than relying only on `normalize().startsWith(...)`.
- `AccessControlStorageSecret` has no production caller. The remaining
  `PlainRootTokenAccessForTests` copy is also unreferenced after the old
  monolithic ACL storage/service test was deleted.
- The only current storage tests cover native Git. Historical Local tests were
  removed with a broad obsolete suite, leaving filesystem load, save, missing,
  overwrite, multi-file, containment, and failure behavior uncharacterized at
  the connector boundary.
- Local extends `OrionEnableServiceSupport`, while native Git does not. Neither
  class overrides lifecycle behavior, so the reason for inheritance is not
  visible here.
- `LocalAccessControlStorage.aclDirectory` accepts `ResourceScheme.Local` as a
  filesystem directory even though the resolver assigns that scheme to native
  Git. Direct construction can therefore give one scheme a second meaning.

## Things to try deleting

- `AccessControlStorageSecret` and the ACL module copy of
  `PlainRootTokenAccessForTests`, after confirming no generated or reflective
  use.
- The `ResourceScheme.Local` branch in `LocalAccessControlStorage`; the resolver
  already gives that scheme one different meaning.
- Retained mutable `BootstrapAccessControlConfig` references inside backend
  instances; replace them with resolved immutable coordinates.
- Public visibility on concrete storage implementations if the next external
  usage audit confirms that only the resolver is a supported construction API.
- Version, save audit metadata, or change subscription from the common storage
  contract if product requirements do not require them for every backend.

## Proposed conceptual model

The minimum coherent model is:

1. One resolver parses and validates one immutable storage specification.
2. One scheme maps to exactly one backend.
3. One backend instance owns fixed normalized paths and a fixed primary path.
4. `load` and `save` share one failure vocabulary and one exact file-set
   invariant.
5. A supported mode exposes only guarantees it actually implements.

The remaining product decision is whether filesystem content is an
authoritative mutable ACL store or a bootstrap source. Do not preserve optional
versions, ignored audit metadata, and silent notifications as a compromise
between those models.

## Incremental migration path

1. Restore focused characterization tests for Local load, save, missing files,
   overwrite, multiple files, primary path, and resolver routing.
2. Delete unused helpers and the duplicate filesystem interpretation of
   `local:`.
3. Introduce one storage-neutral repository-name parser below transports and
   connectors; normalize separators before rejecting dot segments.
4. Harden Local path handling against traversal and symlink escape.
5. Resolve configuration into immutable backend coordinates at construction
   and reject empty, duplicate, aliased, escaping, and non-primary paths before
   any I/O.
6. Make saved snapshot keys equal the configured path set and define one save
   failure vocabulary shared with load.
7. Decide whether multi-file filesystem publication must be atomic and either
   implement one generation switch or explicitly narrow the contract.
8. Decide, using actual consumers, whether version, audit metadata, and change
   notification are mandatory; then narrow the common interface accordingly.

Each step can be verified independently. No large storage format migration is
necessary unless atomic filesystem generations are selected.

## Do not change

- Preserve `NOT_FOUND` as distinct from a general backend failure. Bootstrap
  creation decisions need that distinction even if the representation changes.
- Preserve byte-for-byte file contents and deterministic configured path order.
  ACL parsing and normalization belong above the connector boundary.
- Preserve native Git's create-race recovery: `FILE_ALREADY_EXISTS` followed
  by `find` is useful idempotent provisioning behavior if creation remains.
- Preserve filtering native Git notifications by the configured ref and the
  ability to close a registered subscription.
- Preserve Local traversal checks while strengthening canonicalization,
  physical containment, and alias detection.

## Open questions

- Is filesystem content an authoritative writable ACL store or only a
  bootstrap/import source?
- Must one multi-file ACL load observe a single revision, and must save be
  atomic across all configured paths?
- Does any consumer use snapshot versions for optimistic concurrency, caching,
  or diagnostics?
- Must every live backend support change notification, or is polling/restart
  acceptable for filesystem content?
- Is `BootstrapAccessControlConfig` ever mutated or replaced while a storage is
  live?
- Are concrete storage classes consumed as a public API outside this module,
  or can only `AccessControlStorageResolver` remain public?

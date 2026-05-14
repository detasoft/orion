# Git Object Store and Pack Publication

## Goal

Add a JGit-free object storage and pack publication layer for native Git
repositories.

This layer should make parsed, validated, and indexed Git objects durable and
visible to readers in a controlled order. It is the missing repository storage
boundary between raw pack parsing/reconstruction and higher-level operations such
as `saveFiles`, receive-pack, upload-pack, reachability queries, and projection
rebuilds.

The first implementation should support local native repositories. The storage
contract should also fit S3-compatible storage so the same repository backend can
later run against MinIO or AWS S3 without changing Git protocol code.

## Current State

The S3 pack storage client plan defines a primitive for streaming pack bytes to
S3 and reading byte ranges back.

The pack parser, delta reconstruction, and pack index plans define how to parse
incoming packs, reconstruct final object ids, and build lookup indexes.

The native object model plan defines parsers for commit, tree, tag, and blob
content, but not a durable repository object store.

The native repository backend plan names `GitObjectStore`, `GitPackStore`, and
`GitPackIndexStore`, but leaves their storage contract and publication rules open.

The receive-pack plan requires incoming pack bytes to be persisted and indexed
before refs are updated. It also notes that failed ref updates can leave stored
but unreachable objects for later maintenance.

The reachability plan depends on object lookup and graph readers that can see
only validated, published objects.

Today the JGit-backed repository path owns object insertion, pack storage, and
visibility. Native Git needs explicit Orion-owned storage rules.

## Non-Goals

Do not implement raw pack parsing, delta reconstruction, pack index parsing, ref
storage, upload-pack, or receive-pack in this plan.

Do not implement garbage collection or repacking here. This plan should record
the metadata needed for later maintenance.

Do not require loose-object storage in the first implementation. Packs plus
indexes are enough for native receive-pack and `saveFiles`.

Do not make the storage layer responsible for authorization or branch policy.

Do not depend on JGit in production code. Tests may compare generated or stored
objects with Git CLI/JGit fixtures.

Do not make S3 the first required backend. Design for it, but start with local
storage where atomic rename and fixtures are simpler.

## Storage Boundary

Introduce a small backend-neutral object storage boundary:

- `GitObjectStore`;
- `GitPackStore`;
- `GitPackIndexStore`;
- `GitObjectDirectory`;
- `GitPackPublicationService`;
- `GitObjectPublicationTransaction`;
- `GitPackManifest`;
- `GitPackMetadata`;
- `GitObjectLocation`;
- `GitObjectVisibility`.

The core responsibilities are:

- stage pack bytes before they are visible;
- validate staged bytes and object metadata;
- store or build pack index bytes;
- publish pack, index, and manifest atomically from reader perspective;
- expose object lookup only for validated published packs;
- keep enough metadata to diagnose failed writes and support later maintenance;
- allow repository readers to open a consistent object directory snapshot.

Higher-level code should not write directly to backend-specific pack keys or
filesystem paths.

## Visibility States

Represent object visibility explicitly.

Suggested states:

- `STAGED`: bytes are being written and are not visible to readers;
- `VALIDATING`: upload finished, parser/indexer is validating the pack;
- `PUBLISHED`: pack, index, and manifest are visible to repository readers;
- `REJECTED`: validation failed and the staged data should be cleaned up;
- `ORPHANED`: pack is valid and published, but no ref currently reaches it;
- `DELETING`: maintenance has selected the pack for removal;
- `DELETED`: metadata tombstone or final state after cleanup.

Only `PUBLISHED` and `ORPHANED` objects should be readable through normal object
lookup. `STAGED` and `VALIDATING` data should be readable only through the active
publication transaction.

`ORPHANED` should not mean invalid. A receive-pack ref update may fail after
objects are stored; those objects can remain valid but unreachable until garbage
collection exists.

## Pack Manifest

Add a manifest record per published pack.

Fields:

- repository id;
- pack id or pack checksum;
- pack byte length;
- pack trailer checksum;
- index id or index checksum;
- object count;
- object id hash algorithm;
- creation time;
- source: receive-pack, save-files, import, rebuild, test fixture;
- validation status;
- visibility state;
- optional transaction id;
- optional ref update ids that first referenced the pack;
- optional list or summary of object ids;
- backend-specific storage location metadata;
- projection update status.

The manifest is the durable reader contract. Readers should list manifests and
then open pack/index data referenced by those manifests.

## Local Storage Layout

For native local repositories, use a layout compatible with the native repository
backend plan:

```text
<repo>/
  packs/
    <pack-id>.pack
    <pack-id>.idx
    <pack-id>.json
  tmp/
    pack-publication/
      <transaction-id>/
        incoming.pack
        incoming.idx
        manifest.json
        validation.json
```

Local publication flow:

1. create a transaction directory under `tmp/pack-publication`;
2. stream pack bytes to `incoming.pack`;
3. fsync or otherwise flush according to repository durability settings;
4. parse and validate the pack;
5. build or verify `incoming.idx`;
6. write manifest and validation metadata;
7. move pack, index, and manifest into `packs/`;
8. publish the manifest last, or publish a manifest that references already
   moved pack/index files;
9. remove the transaction directory after successful publication.

Readers should never use `tmp/pack-publication`.

## S3 Storage Shape

For S3-compatible storage, use keys that preserve staged and published
visibility:

```text
repositories/<repo>/tmp/pack-publication/<transaction-id>/incoming.pack
repositories/<repo>/tmp/pack-publication/<transaction-id>/incoming.idx
repositories/<repo>/packs/<pack-id>.pack
repositories/<repo>/packs/<pack-id>.idx
repositories/<repo>/packs/<pack-id>.json
```

S3 publication flow:

1. stream or multipart upload pack bytes under the transaction prefix;
2. abort multipart uploads on failure;
3. validate pack bytes and checksum;
4. upload index and manifest under the transaction prefix;
5. copy pack and index to final keys;
6. write final manifest only after final pack and index keys exist;
7. readers list only final manifests;
8. asynchronously clean transaction keys.

Because S3 does not provide directory rename, final manifest publication is the
visibility boundary. Readers must verify referenced pack/index checksums before
using a manifest.

## Publication Transaction

Use an explicit transaction object for writes.

Lifecycle:

1. `begin(repository, source, options)`;
2. `writePack(InputStream)` or `openPackOutput()`;
3. `completePackWrite()`;
4. `validatePack(parser, deltaResolver, objectModel)`;
5. `buildOrAttachIndex(indexBuilder)`;
6. `writeManifest()`;
7. `publish()`;
8. `abort()` or `cleanup()`.

The transaction should be idempotent where possible. Retrying `abort()` should
not fail if staged data is already gone. Retrying `publish()` after partial
backend failure should either complete publication or return a typed uncertain
state requiring repair.

The transaction should expose staged object lookup only to validation code that
needs to resolve pack-local objects.

## Validation Before Publication

A pack must pass storage validation before it becomes visible.

Validation should include:

- pack header and version;
- object count matches parsed entries;
- pack trailer checksum;
- decompression of every entry within limits;
- delta reconstruction for all entries;
- final object id calculation;
- object type validation;
- commit/tree/tag parser validation where required by caller;
- index entry count and sorted object ids;
- index checksum;
- pack checksum recorded in the index;
- duplicate object handling policy;
- optional graph closure validation for receive-pack.

Graph closure can be caller-specific. The storage layer can publish a valid pack
that is not currently reachable from refs, but receive-pack should not update a
ref until its target graph is valid.

## Object Lookup

`GitObjectDirectory` should provide lookup over published manifests:

- exact object id lookup;
- optional abbreviated id lookup with ambiguity reporting;
- open compressed pack entry range;
- open inflated object content through reconstruction where needed;
- read commit/tree/tag summaries for graph services;
- list packs and metadata for maintenance.

Readers should use a snapshot view:

- list manifests at snapshot creation;
- load pack indexes lazily;
- ignore packs published after the snapshot unless the caller asks to refresh;
- fail clearly if a manifest references missing or mismatched pack/index data.

This keeps upload-pack, path resolution, and projection rebuilds deterministic
while writes are happening.

## Duplicate Objects

Git repositories can contain the same object in multiple packs.

Policy:

- allow duplicate object ids across packs;
- prefer a deterministic location order for reads;
- keep all pack manifests valid;
- report duplicate locations in diagnostics;
- allow future maintenance to compact duplicates.

The first read preference can be newest published pack first or stable manifest
order. The choice should be explicit and covered by tests.

## Integration With Receive-Pack

Receive-pack should use pack publication like this:

1. parse receive commands and policy before reading pack where possible;
2. begin a pack publication transaction;
3. stream incoming pack bytes into staged storage with size limits;
4. validate pack, deltas, index, and command target object ids;
5. publish the pack and index;
6. open an object directory snapshot that includes the new pack;
7. validate graph closure and fast-forward rules;
8. update refs atomically;
9. mark the pack as referenced or leave it orphaned if ref updates fail.

Refs must never point at objects that are still staged or validating.

If publication succeeds but ref update fails, report the ref failure and keep the
pack visible as valid orphaned data. Later maintenance can remove it after a
retention period.

## Integration With SaveFiles

Native `saveFiles` should use the same publication flow:

1. build blob, tree, and commit objects;
2. build a no-delta pack from those objects;
3. publish pack and index;
4. verify new commit is readable from the object directory snapshot;
5. update the target branch with expected old id;
6. mark the pack as referenced or orphaned based on ref update result.

The pack builder should not update refs directly. This storage layer should not
create commits directly.

## Integration With Upload-Pack

Upload-pack should read only published objects.

The upload-pack path should:

- create an object directory snapshot at request start or negotiation start;
- resolve refs through ref storage;
- use reachability/object enumeration over the snapshot;
- open selected object contents or existing pack entries;
- build response packs from snapshot data.

Upload-pack should not read staged incoming receive-pack data from another
request. That prevents clients from seeing objects before refs or policy expose
them.

## Integration With Projections

After publication, the storage layer should emit enough information for derived
models:

- pack id;
- object ids and types;
- commit ids and parent ids when already parsed;
- root tree ids;
- tag targets;
- source operation;
- visibility state.

The commit information projection can consume this event synchronously for small
internal writes or asynchronously after large receives.

Projection failure must not roll back object publication or ref updates after
they are already complete. It should mark projection status stale and schedule a
rebuild.

## Quarantine

Use quarantine semantics for untrusted incoming packs.

Quarantined data:

- is isolated by transaction id;
- is not visible to normal object lookup;
- can be read by validation code;
- is deleted or marked rejected on pack-level failure;
- is promoted only after pack and index validation pass.

This prevents malformed pushes from affecting repository readers and keeps
authorization/validation failures from exposing staged objects.

Internal `saveFiles` writes may use the same staging mechanism even though the
objects are trusted. A single publication path reduces edge cases.

## Repair and Recovery

Startup or maintenance should detect interrupted publications.

Repair cases:

- staged transaction exists with no final manifest: abort or resume validation;
- final pack exists without final index: rebuild index or mark repair needed;
- final index exists without final pack: delete index or mark repair needed;
- final manifest references missing pack/index: hide manifest and report
  corruption;
- manifest checksum does not match pack/index bytes: hide manifest and report
  corruption;
- transaction marked publishing but final manifest uncertain: verify final keys
  and complete or roll back visibility.

Normal readers should ignore incomplete or corrupt manifests. Administrative
diagnostics should expose enough detail to repair the repository.

## Locks and Concurrency

Pack publication should allow concurrent readers.

Writers:

- use unique transaction ids;
- avoid global repository write locks while streaming large pack bytes;
- coordinate final manifest names to avoid collisions;
- publish manifests atomically from reader perspective;
- do not update refs inside the storage transaction.

Readers:

- use object directory snapshots;
- tolerate new manifests appearing after the snapshot;
- fail typed errors if a manifest becomes unreadable during a read.

Ref updates remain a separate atomic operation. Storage publication and ref
updates together form a higher-level repository operation but not a single
backend transaction.

## Metrics and Diagnostics

Record:

- pack byte size;
- object count;
- validation duration;
- index build duration;
- publish duration;
- backend retry count;
- number of duplicate objects;
- source operation;
- final visibility state;
- cleanup failures.

Errors should include repository id, pack id, transaction id, phase, backend
operation, and retryability. They must not include object contents, credentials,
or raw request payloads.

## Implementation Phases

Phase 1: Storage interfaces and manifest model.

Define `GitPackStore`, `GitPackIndexStore`, `GitObjectDirectory`,
`GitPackManifest`, and typed publication errors without backend-specific code.

Phase 2: Local staged pack writes.

Implement local transaction directories, staged pack writes, abort cleanup, and
basic manifest persistence.

Phase 3: Validation integration.

Wire raw pack parser, delta resolver, object model validation, and index builder
into the publication transaction.

Phase 4: Local publication.

Publish pack, index, and manifest into the local native repository layout.
Expose only final manifests to object directory readers.

Phase 5: Object directory snapshots.

Implement exact object lookup across published pack indexes with deterministic
duplicate handling and typed missing/corrupt errors.

Phase 6: Receive-pack integration.

Use quarantine publication for incoming packs and leave successful-but-unreferenced
packs marked orphaned when ref updates fail.

Phase 7: SaveFiles integration.

Publish packs generated from internal object changes before atomic ref updates.

Phase 8: Projection events.

Emit pack publication events and mark projection status for synchronous update or
asynchronous rebuild.

Phase 9: S3 publication adapter.

Map the same transaction and manifest contract onto S3 staged keys, final keys,
conditional metadata writes, and repair diagnostics.

Phase 10: Repair tooling.

Add startup/maintenance scans for interrupted transactions, mismatched manifests,
missing indexes, and orphan retention.

## Verification

Cover at least these cases:

- staged local pack is not visible to object lookup;
- abort removes staged local transaction data;
- malformed pack is rejected before manifest publication;
- valid no-delta pack publishes pack, index, and manifest;
- readers see only packs with final manifests;
- object lookup finds objects from a published pack by exact id;
- duplicate object ids across packs use deterministic read preference;
- missing pack referenced by a manifest produces a typed corruption error;
- missing index can be rebuilt or reported according to policy;
- receive-pack cannot update refs before pack publication;
- receive-pack failed ref update leaves valid objects orphaned and refs unchanged;
- internal save publishes generated objects before branch update;
- object directory snapshot remains stable while another pack is published;
- interrupted local transaction is detected on repair scan;
- interrupted S3 transaction can be ignored, aborted, or resumed by repair logic;
- final manifest publication is the visibility boundary for S3;
- projection event contains pack id, object count, source, and visibility state;
- production storage code has no JGit dependency.

## Open Questions

Should pack manifests store the full object-id list, a compact summary, or only
the index checksum and object count?

Should local publication use final manifest as the only visibility boundary, or
should pack/index filenames also use hidden temporary names until publication?

Should duplicate object read preference be newest-pack first or stable
oldest-pack first?

How long should orphaned packs be retained before a future garbage collector can
remove them?

Should object directory snapshots be explicit closeable resources, or simple
immutable value snapshots over manifest ids?

How much repair should happen automatically at startup versus requiring an
administrative command?

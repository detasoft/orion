# Git Repository Events, Reflog, and Audit

## Goal

Add a detailed plan for Git repository events, durable reflog-style records, and
audit integration across native Git operations.

The native Git backend needs a single event model that can be consumed by
existing Orion events, commit projections, mirror synchronization, maintenance,
logging/audit sinks, and tests. Today event behavior is spread across
receive-pack, ref storage, save-files, upload-pack, and logging plans.

This plan defines when Git events are emitted, what they contain, how they relate
to canonical ref/object state, how durable reflog records are stored, and how
failures should affect repository operations.

## Current State

`GitInternalService` publishes:

- `GitUploadOrionEvent` after upload-pack completes with upload statistics;
- `GitReceiveOrionEvent` after receive-pack reports ref updates.

`JGitRepository.receive()` converts JGit `ReceiveCommand` values into
`GitRefUpdate` records and calls `GitReceiveRequest.afterReceive(...)`.

`GitRefUpdate` currently contains:

- ref name;
- old object id;
- new object id;
- update type;
- result.

The ref storage plan says the native ref store should publish ref update event
metadata after successful writes and later add a durable reflog sidecar.

The native receive-pack plan says receive events should match the JGit-backed
event shape and should be published after ref updates are attempted.

The native save-files plan says internal saves should publish repository ref
update events after CAS results are known.

The commit projection plan consumes pack publication and ref update metadata.

The logging concept describes generic event/audit sinks, but does not define a
Git-specific event schema.

What is missing is a single Git event and reflog plan that connects these pieces
without making every Git feature invent its own event shape.

## Non-Goals

Do not implement ref storage, pack publication, upload-pack, receive-pack,
save-files, projection, mirror synchronization, or logging sinks in this plan.

Do not make event or reflog records the source of truth for repository state.
Refs and Git objects remain canonical.

Do not require durable reflog writes for the first native ref update milestone.
Start with correct in-process event behavior, then add durable records.

Do not block successful Git protocol operations indefinitely on external audit
sinks.

Do not expose credentials, hidden ref names, object contents, private key paths,
or raw pack data in user-visible event records.

Do not depend on JGit in production code. Tests may compare event records against
JGit-backed behavior.

## Event Layers

Separate three related layers:

- operation events: high-level Orion events such as upload, receive, save-files,
  migration, and maintenance;
- ref update events: one record per attempted ref update result;
- durable reflog/audit records: append-only records persisted for diagnostics,
  audit, and rebuild support.

Operation events can group multiple ref updates. Ref update events are the
common unit consumed by projections and mirrors. Durable reflog records are
optional for correctness but useful for audit and recovery diagnostics.

## Service Boundary

Introduce Git event services and records:

- `GitRepositoryEventService`;
- `GitRepositoryEventPublisher`;
- `GitRepositoryEventSink`;
- `GitRepositoryEventRecord`;
- `GitRepositoryOperationEvent`;
- `GitRefUpdateEvent`;
- `GitUploadEvent`;
- `GitReceiveEvent`;
- `GitSaveFilesEvent`;
- `GitPackPublicationEvent`;
- `GitRepositoryMigrationEvent`;
- `GitRepositoryMaintenanceEvent`;
- `GitReflogStore`;
- `GitReflogRecord`;
- `GitEventDeliveryPolicy`.

The service should provide:

- in-process event publishing;
- optional durable reflog append;
- event-to-Orion-event adaptation;
- event-to-projection/mirror notifications;
- event diagnostics and metrics.

Feature code should create domain events. The event service decides where they
are delivered.

## Event Identity

Every event should have stable identity fields:

- event id;
- repository id;
- repository name;
- event type;
- source operation id;
- source operation kind;
- created time;
- actor identity when available;
- request id or correlation id;
- backend kind;
- repository format version.

Event ids should be unique enough for idempotent consumers. A deterministic id
can be derived from operation id plus sequence number when an operation is
replayed. Otherwise use generated ids and expose source operation id for
deduplication.

## Actor Model

Represent actor information without assuming every operation came from an
interactive user.

Actor fields:

- actor type: user, application token, internal service, migration, maintenance,
  anonymous transport if ever applicable;
- user id or subject id;
- display name when safe;
- authentication method where safe;
- source address or transport id when relevant;
- impersonation/delegation marker if Orion supports it later.

Internal operations such as projection rebuilds or maintenance should not pretend
to be Git users. They should use internal actor records.

## Ref Update Event

Define a native ref update event record that can map to existing `GitRefUpdate`.

Fields:

- repository id/name;
- ref name;
- old target id;
- new target id;
- expected old target id;
- update type: create, update, update-non-fast-forward, delete, no-op;
- result: ok, rejected, stale, lock failure, missing object, policy denied,
  not attempted, storage failure, unknown;
- force flag;
- fast-forward flag when known;
- object validation status;
- policy decision id or summary;
- actor;
- message;
- source operation kind;
- source operation id;
- timestamp;
- ref store snapshot/version before update;
- ref store snapshot/version after update when available.

The compatibility adapter should map this richer record to the current
`GitRefUpdate` shape for `GitReceiveOrionEvent`.

## Operation Event Types

Receive-pack event:

- repository;
- actor;
- command count;
- pack publication id;
- unpack status;
- per-ref update events;
- protocol capabilities selected;
- side-band/report-status diagnostics;
- operation result.

Save-files event:

- repository;
- actor/author;
- target ref;
- old commit id;
- new commit id;
- file count;
- normalized paths when safe;
- pack publication id;
- ref update event;
- operation result.

Upload-pack event:

- repository;
- actor;
- wanted object count;
- advertised ref count when safe;
- selected protocol version;
- selected capabilities;
- object count sent;
- pack bytes sent;
- access-check result;
- operation result.

Pack publication event:

- repository;
- pack id;
- object count;
- source operation;
- visibility state;
- validation result.

Migration and maintenance events:

- migration/maintenance run id;
- operation phase;
- result;
- affected refs/packs/projections counts;
- failure category.

## Event Timing

Event timing must match repository state.

Rules:

- pack publication event fires after pack/index/manifest are published or a
  failed publication result is known;
- ref update event fires after ref update attempt result is known;
- receive-pack operation event fires after report-status outcome is known;
- save-files event fires after ref update result is known;
- upload-pack event fires after upload completes successfully or fails before
  data is sent according to policy;
- projection update event fires after projection update result is known;
- maintenance/migration events fire at phase boundaries and final result.

Do not publish a successful ref update event before objects are durable and the
ref compare-and-set has succeeded.

Rejected ref commands can be published as attempted/rejected events when the
operation needs audit parity with current receive-pack behavior. They must not be
misrepresented as successful ref state changes.

## Event Ordering

Preserve causal ordering inside one operation:

1. pack publication result;
2. ref update attempts;
3. projection notification;
4. operation summary event;
5. audit/log sink delivery.

For multi-ref receive-pack:

- keep command order from the client where meaningful;
- record ref store transaction order if different;
- include sequence numbers;
- ensure atomic transaction success/failure is represented consistently for all
  commands.

Across concurrent operations, global ordering is best-effort unless the durable
event store provides monotonic sequence numbers. Consumers should use ref store
versions and event ids for correctness.

## Delivery Semantics

Define delivery tiers:

- best-effort in-process Orion event delivery;
- required internal notifications for projection/mirror queues where configured;
- optional durable reflog/audit append;
- optional external log/export sink.

Repository correctness must not depend on in-process event delivery. If an event
consumer fails after refs are updated, canonical refs and objects remain valid.

Consumers that need recovery, such as projection and mirror synchronization,
should be able to rebuild from canonical refs/objects or read durable event
records when available.

## Failure Policy

Failure behavior should be explicit.

Recommended defaults:

- ref/object writes do not roll back because an in-process event handler fails;
- durable reflog append failure after successful ref update marks audit degraded
  and emits diagnostics;
- if policy requires durable reflog before ref update, fail before the ref write
  rather than after it;
- external audit sink failures never block Git protocol operations by default;
- projection event handling failure marks projection stale and schedules rebuild;
- mirror event handling failure queues retry or marks mirror sync stale.

For high-assurance deployments, allow stricter policy where ref updates require
durable local reflog append before success is reported. That policy must be
opt-in and carefully tested.

## Durable Reflog

Add a backend-neutral reflog store.

Reflog records should be append-only and include:

- repository id/name;
- ref name;
- old target;
- new target;
- actor;
- message;
- source operation kind;
- source operation id;
- timestamp;
- result;
- event id;
- record checksum or sequence id.

Canonical refs do not depend on reflog records. Missing reflog should not make a
repository unreadable.

Durable reflog can support:

- audit review;
- debugging failed pushes/saves;
- projection/mirror catch-up hints;
- migration reports;
- maintenance diagnostics.

It should not be required to reconstruct canonical branch state unless a future
recovery plan explicitly defines that use.

## Local Reflog Storage

Local storage options:

- one append-only file per ref;
- one append-only JSON-lines file per repository;
- compact binary records managed by a reflog store;
- segmented files with rotation.

First implementation recommendation:

- JSON-lines or length-prefixed records for inspectability;
- per-repository segments under `logs/` or `events/`;
- fsync policy configurable;
- rotation by size and age;
- checksums per record or segment;
- repair tool can skip corrupt tail records.

Local layout example:

```text
<repo>/
  events/
    git-events-000001.jsonl
  logs/
    refs/
      heads/
        main.log
```

The exact layout should avoid conflicting with Git-compatible `.git/logs`
semantics unless Orion explicitly chooses compatibility.

## S3 Reflog Storage

S3 storage should avoid read-modify-write appends to a single object.

Recommended shape:

```text
repositories/<repo>/events/<date>/<event-id>.json
repositories/<repo>/reflog/<ref-hash>/<timestamp>-<event-id>.json
```

Properties:

- immutable event objects;
- conditional create for each event id;
- prefix listing for retention/maintenance;
- optional compacted summary objects later;
- no single hot object for high write concurrency.

Ordering should use timestamps plus sequence numbers where available, but S3
listing order should not be trusted as the only source of event order.

## Event Adapters

Keep compatibility with existing Orion events.

Adapters:

- native receive-pack events to `GitReceiveOrionEvent`;
- native upload-pack stats to `GitUploadOrionEvent`;
- native save-files ref update events to an existing or new Orion event type;
- pack publication and maintenance events to logging/audit sink records;
- ref update events to projection updater messages;
- ref update events to mirror synchronization queue.

The current `GitReceiveOrionEvent` groups a list of `GitRefUpdate` records. Keep
that shape for compatibility while richer native event records exist internally.

## Projection Consumer

Commit projection should consume:

- pack publication events for new object facts;
- successful ref update events for ref snapshot updates;
- failed/incomplete events only as diagnostics;
- projection rebuild requests from maintenance.

Projection consumption should be idempotent:

- repeated event id is ignored;
- older ref store version does not move projection backward;
- missing event can be recovered by comparing canonical refs/object manifests.

Projection failure should mark projection stale, not fail Git operations that
already committed.

## Mirror Consumer

Mirror synchronization should consume successful ref update events.

Rules:

- include enough ref update metadata to decide refspec matches;
- distinguish delete, create, fast-forward, and force updates;
- queue mirror work idempotently by event id and ref name;
- do not enqueue hidden/internal refs unless mirror policy includes them;
- failed mirror queueing marks mirror stale and schedules reconciliation.

Mirror sync should also support full reconciliation from refs if events were
missed.

## Upload Events

Upload-pack does not change repository state, but it is important for audit and
diagnostics.

Record:

- repository;
- actor;
- protocol version;
- selected capabilities;
- access-check result;
- wanted object count;
- pack object count;
- bytes sent;
- duration;
- failure category.

Do not record raw wanted object ids in user-visible logs unless configured,
because object ids may reveal hidden branch activity. Internal diagnostics can
include them under restricted logs.

## Privacy and Redaction

Event records must be safe by default.

Redact or omit:

- credentials;
- authorization headers;
- private key paths;
- raw object contents;
- raw pack bytes;
- hidden ref names in user-visible logs;
- file paths if caller policy marks repository content sensitive.

Keep object ids and ref names in internal event records when needed for
projections and mirrors. Audit/log sinks can apply a redaction policy when
exporting.

## Idempotency and Replay

Events should support replay where practical.

Rules:

- event ids are unique;
- consumers store last processed id or source operation id when needed;
- duplicate delivery is safe;
- out-of-order delivery is handled by ref store version checks;
- durable event records can be replayed after restart;
- replay never mutates canonical refs directly unless a consumer explicitly owns
  derived state.

Projection and mirror consumers should be able to rebuild from canonical state if
event replay is incomplete.

## Metrics

Record:

- events published by type;
- event delivery duration;
- failed event handlers by type;
- durable reflog append duration;
- durable append failures;
- event queue depth where queues exist;
- projection consumer lag;
- mirror consumer lag;
- audit sink lag;
- redaction count;
- dropped best-effort events.

Metrics should distinguish repository operation success from event delivery
success.

## Implementation Phases

Phase 1: Native event model.

Define Git operation events, ref update events, actor records, event ids,
operation ids, and compatibility mapping to current `GitRefUpdate`.

Phase 2: Event service boundary.

Add `GitRepositoryEventService` and in-memory/test publishers. Keep canonical
operations independent from event consumers.

Phase 3: Receive-pack adapter.

Have native receive-pack produce rich ref update and operation events, then adapt
them to `GitReceiveOrionEvent`.

Phase 4: Save-files adapter.

Have native save-files produce ref update and operation events after CAS result.
Define whether a new Orion event type is needed for internal saves.

Phase 5: Upload-pack event.

Map native upload stats and failures to upload operation events and
`GitUploadOrionEvent` compatibility behavior.

Phase 6: Projection consumer integration.

Use pack publication and successful ref update events to drive incremental
projection updates, with idempotent replay and stale fallback.

Phase 7: Mirror consumer integration.

Use successful ref update events to enqueue mirror synchronization and provide
reconciliation fallback.

Phase 8: Local durable reflog.

Implement local append-only reflog/event storage with record validation,
rotation, and repair behavior.

Phase 9: S3 durable event storage.

Store immutable event/reflog records under S3 prefixes with conditional create
and maintenance retention.

Phase 10: Audit/log sink integration.

Convert Git events into structured logging/audit records with redaction policy
and bounded failure behavior.

Phase 11: Replay and maintenance.

Add event replay tooling for projections/mirrors and maintenance checks for
missing or corrupt durable records.

## Verification

Cover at least these cases:

- production native event model has no JGit dependency;
- receive-pack successful create emits one ref update event and one receive
  operation event;
- receive-pack rejected non-fast-forward emits a rejected ref update result
  without claiming ref success;
- multi-ref receive-pack preserves command order and sequence numbers;
- atomic receive-pack success/failure is represented consistently for all refs;
- save-files success emits create/update ref event with source `save-files`;
- save-files stale CAS does not emit successful ref update event;
- upload-pack success emits upload event with object/byte statistics;
- upload-pack authorization failure is sanitized and does not leak hidden refs;
- projection consumer processes successful ref events idempotently;
- duplicate event delivery does not duplicate projection state;
- mirror consumer queues only refs allowed by mirror policy;
- event handler failure after successful ref update does not roll back canonical
  ref state under default policy;
- durable local reflog append writes a parseable record;
- corrupt local reflog tail is reported and does not make refs unreadable;
- S3 event storage writes immutable per-event records with conditional create;
- audit/log redaction removes credentials and raw object contents;
- durable event replay can rebuild projection hints for a simple history;
- missing durable event records trigger reconciliation from canonical refs;
- existing `GitReceiveOrionEvent` compatibility tests still pass for native
  receive fixtures.

## Open Questions

Should native `saveFiles(...)` publish a new Orion event type, reuse
`GitReceiveOrionEvent`, or only emit internal Git repository events?

Should durable reflog append be best-effort by default, or required for selected
repositories before ref updates are reported as successful?

Should reflog records follow Git's traditional per-ref text format, or use an
Orion-specific structured format from the start?

How much object id detail should upload-pack audit records expose by default?

Should event replay be a general Orion event-manager feature, or a Git-specific
facility tied to repository maintenance?

# Native Git Receive-Pack Serving

## Goal

Add JGit-free receive-pack serving so Orion can accept pushes into native
repositories.

This plan covers server-side `git-receive-pack`: ref advertisement, command
parsing, push capability negotiation, incoming pack ingestion, object validation,
atomic ref updates, report-status responses, and receive events without
delegating to JGit `ReceivePack`.

## Current State

`GitInternalService` parses the initial Git command, checks repository write or
create access, opens or creates the target repository, and calls
`GitRepository.receive()`.

`JGitRepository.receive()` currently creates JGit `ReceivePack`, delegates command
parsing, pack parsing, object insertion, ref updates, and report-status handling
to JGit, then translates JGit receive commands into Orion `GitRefUpdate` events.

The native repository backend plan defines where receive-pack should eventually
land, and the ref storage plan defines compare-and-set ref updates. The pack
parser, delta reconstruction, pack index, and object model plans define how
incoming pack bytes can become durable objects. This plan defines the missing
server-side receive-pack flow that connects those pieces.

## Non-Goals

Do not implement upload-pack in this plan.

Do not implement raw pack parsing, delta reconstruction, object storage, or ref
storage here. This layer orchestrates those services.

Do not remove the JGit receive path immediately. Keep native receive-pack behind
a native backend capability or feature flag until parity tests pass.

Do not implement every optional push capability in the first phase.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code. Tests may compare behavior against Git
CLI or JGit fixtures.

Do not allow force updates unless the caller's access policy and push command
explicitly permit them.

## Protocol Scope

Start with one protocol mode that can accept ordinary Git client pushes:

- advertise refs and capabilities;
- parse old-id/new-id/ref-name command lines;
- read one pack stream;
- parse `report-status`;
- support side-band-64k for progress and errors when negotiated;
- support `delete-refs` only after delete policy is implemented;
- support `atomic` after multi-ref transactions are implemented.

Protocol v0/v1 receive-pack is still widely used by clients. Protocol v2 push is
less central than protocol v2 fetch. The first native receive-pack implementation
should prioritize compatibility with ordinary Git push clients while keeping the
parser structured and testable.

## Capability Model

Advertise only capabilities that are implemented:

- `report-status`;
- `side-band-64k` when response side-band is implemented;
- `delete-refs` only when deletes are supported;
- `atomic` only when ref store multi-ref transactions are available;
- `ofs-delta` when delta reconstruction supports it;
- `push-options` only after Orion has a safe option policy;
- `object-format=sha1`;
- `agent`.

Unsupported client-requested capabilities should fail before pack ingestion when
the protocol requires support.

## Ref Advertisement

Build initial receive-pack advertisement from `GitRefStore`:

- advertise branches and tags;
- advertise `HEAD` where appropriate;
- omit hidden or internal refs;
- include capabilities on the first advertisement line;
- advertise an empty repository correctly;
- use stable ordering for deterministic tests.

For repository creation, the service must initialize repository metadata and
`HEAD` before advertising an empty ref set.

## Command Parsing

Add receive command value objects:

- `GitReceiveCommand`;
- `GitReceiveCommandType`: create, update, delete;
- `GitReceiveCommandPolicy`;
- `GitReceivePackRequest`;
- `GitReceivePackResult`.

Parse and validate:

- old object id;
- new object id;
- ref name;
- zero id semantics for create/delete;
- duplicate commands for the same ref;
- malformed object ids;
- invalid ref names;
- command count limit;
- push options if capability is enabled later.

Commands should be validated before pack ingestion when possible, but missing new
objects can only be verified after pack parsing and object publication.

## Authorization and Policy

Preserve existing Orion repository write/create access checks before opening or
creating repositories.

Add command-level policy hooks:

- branch create allowed;
- branch update allowed;
- branch delete allowed;
- tag create/update/delete policy;
- force push allowed;
- protected ref rules;
- maximum pushed pack size;
- maximum command count.

Policy failure should reject the relevant command and avoid updating refs.

For non-atomic pushes, Git can accept some commands and reject others. Orion
should start conservatively: either require all commands valid before applying, or
clearly document per-command partial behavior. Once `atomic` is advertised, all
commands must be all-or-nothing.

## Incoming Pack Ingestion

Receive-pack must not point refs at objects until the pack is validated and
objects are durable.

Ingestion flow:

1. read incoming pack stream with configured byte limit;
2. parse pack header, entries, and checksum;
3. reconstruct delta objects;
4. compute final object ids and types;
5. validate all command new object ids exist, except deletes;
6. verify object graph closure or allowed thin-pack bases;
7. build or validate pack index;
8. persist pack bytes and index through the repository object store;
9. publish object availability;
10. only then update refs.

If any pack validation step fails, no refs should change.

## Ref Updates

Translate receive commands into `GitRefUpdateRequest` values:

- create: expected old target absent or zero id;
- update: expected old target matches command old id;
- delete: expected old target matches command old id and new id is zero;
- force: only when policy allows non-fast-forward update;
- atomic: use multi-ref transaction when negotiated.

Ref update failures after successful object storage should produce report-status
rejects. Stored but unreachable objects can remain for later garbage collection.

Fast-forward validation should use commit graph or object traversal. If the
native backend cannot determine fast-forward safety, it should reject
non-explicit-force updates rather than silently allowing them.

## Report Status

Implement response generation:

- `unpack ok` or `unpack <error>`;
- `ok <ref>` for successful command;
- `ng <ref> <reason>` for rejected command;
- flush at the end;
- side-band wrapping when negotiated.

Error reasons should be compatible with Git clients but sanitized. Do not leak
hidden branch names, filesystem paths, credentials, or object content.

Call `GitReceiveRequest.afterReceive()` exactly once with all command results
after report-status is known. For pack-level failure before command evaluation,
report command results consistently with existing Orion event expectations.

## Event Semantics

Native receive events should match the current JGit-backed event shape:

- repository name;
- user name;
- ref name;
- old id;
- new id;
- update type;
- result.

Events should be published only after ref updates are attempted and results are
known. Successful object ingestion without ref changes should not publish a
successful ref update.

## Empty Repository and Create

For a missing repository, `GitInternalService` already requires create access
before `findOrCreate`. Native receive-pack must handle the newly created empty
repository:

- initialize metadata;
- initialize unborn `HEAD`;
- advertise no branch refs;
- accept first branch create if policy allows;
- store incoming objects;
- update the branch ref;
- keep `HEAD` symbolic target consistent with default branch.

If the first push creates a branch other than the default, decide whether `HEAD`
should remain unborn default branch or update only through explicit repository
configuration.

## Delete and Tag Policy

Add delete support only with explicit policy:

- branch delete;
- tag delete;
- current/default branch delete restrictions;
- protected refs;
- expected old id checks.

Tag updates are often more restrictive than branch updates. The first native
receive-pack can allow tag create and reject tag update/delete unless configured.

## Phased Plan

Phase 1: Advertisement and command parser fixtures.

Generate receive-pack advertisements from an in-memory ref store and parse command
streams from scripted clients. Cover malformed commands, duplicate refs, and
capability selection.

Phase 2: Policy validation.

Add command-level create/update/delete/force/tag/protected-ref decisions before
pack ingestion.

Phase 3: Pack ingestion orchestration.

Read incoming pack bytes, call raw pack parser, delta resolver, object model, pack
index builder, and object store through test doubles.

Phase 4: Object and command validation.

Verify command new ids exist after ingestion, old ids still match current refs,
and fast-forward policy is enforced.

Phase 5: Ref update application.

Apply single-ref and multi-ref updates through `GitRefStore`, with atomic mode
only advertised when transactions are available.

Phase 6: Report-status response.

Emit unpack status and per-ref status with side-band support where negotiated.

Phase 7: Receive events.

Call `afterReceive` and publish receive events with the same shape and result
mapping as the JGit-backed path.

Phase 8: Empty repository first push.

Support first branch creation into a newly created native repository.

Phase 9: Real Git client compatibility.

Run controlled Git CLI push scenarios against native receive-pack over local
transport and SSH/native transport where available.

Phase 10: Native repository integration.

Wire receive-pack serving behind `GitRepository.receive()` for the native backend
and broaden parity tests against JGit-backed behavior.

## Open Questions

Should native receive-pack initially require all command refs to validate before
applying any ref update, even when the client did not request `atomic`?

Should tag update/delete be rejected by default until explicit policy exists?

Should first push to a non-default branch update `HEAD`, or should `HEAD` remain
the configured default unborn branch?

How should fast-forward checks behave before commit graph projection is available:
walk canonical commits on demand or reject uncertain updates?

Should incoming pack bytes be stored exactly as received, repacked, or normalized
after validation?

How long should unreachable packs from rejected ref updates be retained before
garbage collection?

## Verification

Cover at least these cases:

- production native receive-pack code has no JGit dependency;
- empty repository advertisement is valid for a first push;
- command parser handles create, update, delete, malformed ids, invalid refs, and
  duplicate ref commands;
- unsupported capabilities are rejected before pack ingestion;
- protected ref policy rejects matching commands;
- pack checksum failure produces `unpack <error>` and updates no refs;
- delta reconstruction failure updates no refs;
- command new id missing after pack ingestion rejects that ref;
- create command succeeds only when old id is zero and ref is absent;
- update command succeeds only when old id matches current ref;
- stale old id rejects without changing the ref;
- non-fast-forward update rejects unless force is explicitly allowed;
- delete command requires delete policy and matching old id;
- atomic push either updates all refs or none;
- report-status response contains `ok` and `ng` entries as expected;
- side-band fatal errors do not corrupt report-status or pack parsing;
- receive events include old id, new id, type, and result for all commands;
- first push can create the default branch in a native repository;
- failed ref update after object storage leaves refs unchanged;
- native Git client push fixture succeeds and can be fetched back;
- JGit-backed and native-backed receive fixtures produce matching ref update
  events for deterministic scenarios.

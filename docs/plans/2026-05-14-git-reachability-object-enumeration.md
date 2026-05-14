# Git Reachability and Object Enumeration

## Goal

Add a JGit-free reachability and object enumeration layer for native Git
repositories.

This layer should answer the graph questions that upload-pack, receive-pack,
pack building, ref updates, and the commit information model all need:

- whether one commit is an ancestor of another commit;
- which commits are reachable from a set of tips;
- which objects must be sent for a clone or fetch;
- which objects can be omitted because the client already has them;
- whether a ref update is a fast-forward;
- which refs or branches make a wanted object reachable for access checks.

The first implementation should be correct, bounded, and independent from JGit.
Performance optimizations such as bitmaps and generation-number indexes can come
after the native graph walk is covered by tests.

## Current State

The commit information model plan defines reachability and commit graph data as
part of a rebuildable projection, but it does not define the repository service
that native Git operations should call.

The native upload-pack plan needs object negotiation, wanted/have subtraction,
branch access resolution, and full object closure enumeration before it can build
response packs.

The native receive-pack plan needs fast-forward validation and pushed object
graph checks before refs can point at newly uploaded objects.

The pack builder plan needs a caller-provided object set, but the selection of
that set is intentionally outside pack writing.

The ref storage plan can enforce compare-and-set and atomic updates, but it
expects a higher-level service to decide fast-forward safety when commit graph
data is required.

Today JGit implicitly provides these graph walks through `UploadPack`,
`ReceivePack`, and repository object APIs. Native Git needs an Orion-owned
service boundary for the same behavior.

## Non-Goals

Do not implement pack parsing, delta reconstruction, pack indexing, object
storage, ref storage, upload-pack, or receive-pack in this plan.

Do not make the commit information projection the source of truth. Git objects
and refs remain canonical.

Do not require bitmap indexes, Bloom filters, or generation-number indexes for
the first version.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code. Tests may compare native behavior
against Git CLI or JGit-backed fixtures.

Do not solve garbage collection or object pruning here. Enumeration may expose
unreachable objects to a later maintenance plan, but should not delete them.

## Service Boundary

Add a repository-local reachability service that can be used by native transport
and storage code without knowing the backend layout.

Candidate interfaces and value objects:

- `GitReachabilityService`;
- `GitCommitGraphReader`;
- `GitObjectClosureReader`;
- `GitReachabilityQuery`;
- `GitObjectClosureRequest`;
- `GitObjectClosureResult`;
- `GitReachabilityResult`;
- `GitTraversalLimit`;
- `GitTraversalDiagnostics`;
- `GitReachableRef`;
- `GitObjectSelectionReason`.

The service should expose operations such as:

- `isAncestor(baseCommitId, tipCommitId)`;
- `isFastForward(oldCommitId, newCommitId)`;
- `reachableCommits(tips, stopAt, limits)`;
- `reachableRefs(objectId, refFilter, limits)`;
- `selectObjectsForFetch(wants, haves, options)`;
- `objectClosure(tips, excludedObjects, options)`;
- `validateObjectGraphClosure(tips, allowedExternalBases, limits)`.

The API should return typed results rather than raw booleans where callers need
to distinguish "not reachable", "object missing", "object is not a commit",
"traversal limit exceeded", and "projection stale".

## Data Sources

Use canonical repository data first:

- `GitRefStore` for branch, tag, and `HEAD` targets;
- object lookup for object existence and type;
- commit parser for tree id, parent ids, and commit metadata required for graph
  traversal;
- tree parser for tree and blob closure;
- tag parser for annotated tags and peeled targets;
- pack index/object lookup for locating stored objects.

Use derived data as an acceleration layer:

- commit information projection for parent lists, generation numbers, and root
  tree ids;
- ref projection for advertised and hidden ref lookup;
- path/tree indexes for faster tree closure where available;
- future pack bitmaps for object-set acceleration.

If projection data is missing or stale, the service should fall back to
canonical object traversal when that is safe and within configured limits.

## Object Types

Support all ordinary Git object types:

- commit;
- tree;
- blob;
- annotated tag.

Commit traversal should parse parents and root tree ids.

Tree traversal should include nested trees and blobs exactly once per object id.

Annotated tag traversal should include the tag object and follow the target.
Tags pointing to commits should include the reachable commit closure when the
caller requests closure for fetch. Tags pointing directly to trees or blobs
should include those target objects and their required descendants.

Unknown object types, corrupt objects, and malformed object ids should fail with
typed errors before pack generation or ref updates.

## Commit Reachability

Implement ancestry and reachability over the commit graph.

Initial algorithm:

1. validate all input ids exist and are commits where commit traversal is
   required;
2. start from one or more tip commits;
3. walk parent links breadth-first or depth-first with a visited set;
4. stop at explicit boundary commits;
5. return a deterministic result order for tests;
6. enforce maximum visited commits, maximum missing objects, and timeout or
   cancellation limits.

Fast-forward validation is:

- true when the old commit id is reachable from the new commit id;
- false when the new commit exists but does not reach the old commit;
- error when either side is missing, corrupt, or not a commit.

For branch create operations there may be no old commit. For deletes there is no
new commit. Those cases should be handled by receive-pack/ref policy before
calling fast-forward validation.

## Object Closure

Object closure should compute the set of objects needed to materialize selected
tips.

For a commit tip:

1. include the commit;
2. include its root tree;
3. include every nested tree and blob reachable from that root tree;
4. include parent commits and their trees unless traversal is stopped by the
   caller's exclusion set;
5. include annotated tag objects when the requested tip is an annotated tag.

For upload-pack, the closure should support:

- wanted object ids;
- have object ids;
- stop-at commits derived from haves;
- object exclusions derived from objects known to be on the client;
- optional thin-pack base omission only when negotiated later.

For receive-pack validation, the closure should support:

- confirming that command new ids exist after pack ingestion;
- detecting missing parents, trees, blobs, or tag targets;
- allowing external bases only when the protocol and policy permit thin packs.

The first version can over-include reachable objects for correctness, but must
not omit required objects from clone/fetch responses or accept refs that point to
incomplete object graphs.

## Fetch Object Selection

Native upload-pack should call this service after request parsing and access
checks.

Selection flow:

1. normalize wanted ids and have ids;
2. validate wanted ids are visible and exist;
3. resolve wanted ids to reachable refs for `GitFetchAccessRequest`;
4. identify commits reachable from haves;
5. compute object closure from wants;
6. subtract objects reachable from haves;
7. include delta bases unless thin-pack support explicitly permits omission;
8. return a deterministic object list or iterator for the pack builder.

The service should separate negotiation decisions from pack writing. Upload-pack
should not need to know how a closure was computed; it should receive an object
selection result plus diagnostics and statistics.

## Push Validation

Native receive-pack should call this service after incoming pack parsing and
before ref updates.

Validation flow:

1. verify every command new id exists after object ingestion, except deletes;
2. validate new ids have the expected object type for the ref namespace;
3. verify object graph closure for newly introduced commits and tags;
4. validate fast-forward rules for non-force branch updates;
5. return per-command accept/reject decisions with protocol-safe reasons.

Fast-forward checks should happen before ref compare-and-set updates. If a
projection is unavailable and canonical traversal exceeds configured limits, the
safe default for a non-force update is rejection with a clear internal reason.

## Reachable Ref Resolution

Fetch access checks currently need a branch resolver for wanted object ids. The
native service should provide equivalent behavior without JGit.

Resolution should answer:

- which visible branch tips reach a wanted commit;
- whether a wanted tag is visible;
- whether a raw wanted object id is reachable only from hidden refs;
- whether no visible ref reaches the object.

Hidden refs must not be disclosed in error messages. Results passed to access
checks should contain only refs the caller is allowed to know about.

For performance, the first implementation can scan visible refs and walk their
commit graphs. Later versions can use reverse reachability indexes from the
commit information projection.

## Ordering and Streaming

Keep traversal deterministic for tests:

- sort refs by name before walking;
- sort parent ids lexicographically only when Git object order does not define a
  better local order;
- preserve tree entry order as encoded by Git when enumerating trees;
- deduplicate objects by id while preserving first-seen order.

Large repositories should not require fully materializing every intermediate set
when a streaming iterator is enough. The public API may expose both:

- a materialized result for small/test repositories;
- a bounded iterator for upload-pack pack building.

The iterator must surface errors before producing a partial successful pack.

## Limits and Failure Modes

Add explicit limits:

- maximum wanted ids;
- maximum have ids;
- maximum visited commits;
- maximum visited trees;
- maximum emitted objects;
- maximum traversal wall-clock time or cancellation token;
- maximum missing object reports before aborting;
- maximum tag chain depth.

Failures should be typed:

- missing object;
- object type mismatch;
- corrupt object;
- malformed commit/tree/tag;
- traversal limit exceeded;
- hidden or unauthorized object;
- projection stale and fallback unavailable;
- storage read failure.

Transport layers should translate these errors into sanitized protocol messages.
Internal logs can include object ids and repository names, but not credentials or
object contents.

## Projection Integration

The commit information projection should accelerate but not own reachability.

Projection-backed reads can use:

- commit id to parent ids;
- commit id to generation number;
- commit id to root tree id;
- ref to commit id;
- optional reverse ref reachability summaries;
- path/tree indexes for tree closure.

Projection records must include a version marker and rebuild status. If the
projection is rebuilding, missing, or marked stale, callers should either fall
back to canonical objects or fail with a typed "projection unavailable" reason
when fallback is disallowed by limits.

Any projection record that contradicts canonical object ids should be treated as
corruption and should trigger rebuild diagnostics.

## Backend Considerations

The service should work with local filesystem and S3-backed object stores.

Local storage can read loose objects and pack indexes directly through native
object lookup.

S3 storage should avoid many small serial reads when possible:

- batch object metadata lookups;
- reuse pack index readers;
- cache parsed commit headers during a traversal;
- keep tree traversal lazy until closure actually needs tree/blob objects.

Backend-specific caching must not change correctness. Cache misses should behave
the same as uncached canonical object reads.

## Implementation Phases

Phase 1: Object graph read primitives.

Add commit, tree, and tag graph readers over the native object model. Return
typed object summaries needed for traversal without exposing raw parser details.

Phase 2: Commit ancestry queries.

Implement `isAncestor`, `isFastForward`, and bounded `reachableCommits` using
canonical object traversal and visited sets.

Phase 3: Tree and tag closure.

Implement object closure for commits, trees, blobs, and annotated tags. Deduplicate
objects and produce deterministic ordering.

Phase 4: Fetch object selection.

Wire wants/haves subtraction and visible-ref resolution for native upload-pack.
Return an object iterator suitable for the pack builder.

Phase 5: Push graph validation.

Wire command new-id validation, object graph closure checks, and fast-forward
checks for native receive-pack.

Phase 6: Projection acceleration.

Use the commit information model for parent/root-tree reads and generation-aware
traversal. Keep canonical fallback and mismatch diagnostics.

Phase 7: Large repository safeguards.

Add traversal cancellation, metrics, backend read batching, and limit-specific
test coverage.

Phase 8: Optional bitmap-like optimization.

Introduce pack-aware or projection-backed reachability summaries only after the
correct native implementation is stable.

## Verification

Cover at least these cases:

- linear history ancestry: old commit is ancestor of new commit;
- reverse ancestry is not treated as a fast-forward;
- merge history where both parents are reachable;
- branch divergence rejects non-force fast-forward validation;
- missing commit returns a typed missing-object error;
- corrupt commit returns a typed corruption error;
- object closure includes commit, root tree, nested tree, and blobs;
- annotated tag closure includes the tag object and peeled target;
- tag chain depth limits prevent unbounded recursion;
- wants/haves subtraction omits objects already reachable from haves;
- fetch selection includes delta bases when thin-pack is disabled;
- visible branch resolution matches existing JGit-backed access behavior for
  fixtures;
- hidden refs are not disclosed in access or protocol errors;
- receive-pack validation rejects refs pointing at incomplete object graphs;
- projection-backed traversal matches canonical traversal;
- stale projection falls back or fails according to configured policy;
- traversal limits produce deterministic typed failures;
- local and S3-backed repositories return equivalent reachability results;
- production code has no JGit dependency.

## Open Questions

Should fast-forward validation live directly on `GitReachabilityService`, or
should receive-pack own that policy and call a lower-level `isAncestor` query?

What deterministic object order should the pack builder prefer: traversal order,
type grouping, pack locality, or a dedicated pack-ordering service?

Should visible-ref resolution return every visible branch that reaches a wanted
object, or only the nearest/stable branch name needed by access checks?

How strict should receive-pack be about complete graph validation before native
garbage collection and quarantine storage exist?

Which limits should be repository configuration, server-wide configuration, or
hard-coded safety defaults?

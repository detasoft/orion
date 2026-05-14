# Git Partial Clone Filters and Shallow Fetch

## Goal

Add JGit-free support for upload-pack filtering and shallow fetch behavior.

This plan covers both sides Orion needs:

- server-side native upload-pack support for negotiated filters and shallow
  boundaries;
- client-side remote single-file fetch that prefers protocol v2 filtered fetch;
- object selection rules for filtered packs;
- shallow boundary computation and protocol responses;
- interaction with reachability, pack building, delta generation, access policy,
  and S3-backed repositories.

The first target is protocol v2 `filter blob:none` plus simple depth-based
shallow fetch. More filter expressions can be added after the first path is
correct and observable.

## Current State

`docs/plans/2026-05-14-native-git-upload-pack-serving.md` defines a minimal
native upload-pack path, then lists filtered fetch and shallow fetch as later
phases. It does not define a detailed filter model, shallow boundary model,
capability gating, pack closure rules, or fallback behavior.

`docs/plans/2026-05-14-native-remote-git-single-file-fetch.md` depends on
protocol v2 `filter blob:none` to fetch commit and tree data without downloading
all blobs, then fetch the requested blob separately. It also defines a bounded
shallow no-filter fallback.

`docs/plans/2026-05-15-git-pack-delta-generation-thin-pack.md` notes that
filters and shallow fetch affect delta base choices and thin-pack proof. It does
not define the filter or shallow semantics.

`docs/plans/2026-05-15-git-reachability-acceleration-indexes.md` can accelerate
object selection, but canonical traversal remains the source of truth.

Today these behaviors are provided by JGit when Orion uses JGit upload-pack and
JGit repository operations. Native Git needs its own model before advertising
`filter` or `shallow`.

## Non-Goals

Do not implement the base upload-pack protocol parser here. The wire protocol
core and upload-pack serving plans own pkt-line, protocol v2 command parsing,
side-band, and basic request handling.

Do not implement pack writing here. This plan produces object-selection and
protocol-response decisions for the native pack builder.

Do not require partial clone support for all filters in the first milestone.
Start with `blob:none`.

Do not implement a full promisor remote object store for Orion repositories in
the first server-side milestone. Orion server-side repositories should normally
serve from complete canonical storage.

Do not silently turn a single-file remote fetch into an unbounded clone. Every
fallback must be bounded and visible in the result diagnostics.

Do not expose hidden refs, protected objects, or unreachable object ids through
filter or shallow diagnostics.

Do not depend on JGit in production code. Tests may compare behavior against Git
CLI or JGit-backed fixtures.

## Terminology

Filter:

- a client-requested object omission rule for upload-pack;
- examples include `blob:none`, `blob:limit=<n>`, and tree-depth filters;
- this plan implements `blob:none` first.

Filtered pack:

- a pack that intentionally omits objects matching the negotiated filter.

Promised object:

- an object intentionally omitted from a filtered response but expected to be
  available through a later fetch from the same remote.

Shallow fetch:

- a fetch that truncates commit history according to depth, time, or exclude
  rules.

Shallow boundary:

- an included commit whose parents are not included because of shallow rules.

Unshallow boundary:

- a commit previously considered shallow by the client that becomes complete
  after a deepen request.

Closure:

- the set of objects required by selected commits, trees, tags, and blobs after
  applying filter and shallow rules.

## Capability Gating

The server must not advertise capabilities before behavior is implemented.

Advertise `filter` only when:

- request parsing accepts filter lines;
- at least `blob:none` is implemented;
- object selection can omit blobs safely;
- pack generation cannot include omitted blobs by accident;
- upload statistics and diagnostics record filter usage;
- unsupported filters return typed protocol errors.

Advertise `shallow` only when:

- deepen requests are parsed;
- shallow boundary computation is implemented;
- server responses include correct shallow/unshallow information;
- object selection respects truncated history;
- tests cover depth boundaries and existing client shallow state.

Client-side remote fetch must treat capabilities separately:

- if remote advertises `filter`, prefer `filter blob:none`;
- if remote does not advertise `filter`, use configured compatibility fallback;
- if remote advertises `shallow`, bounded fallback can use `deepen`;
- if remote lacks both, strict mode fails and compatibility mode may still refuse
  based on configured byte and object limits.

## Filter Model

Introduce a native filter representation independent from raw protocol strings.

Candidate value objects:

- `GitObjectFilter`;
- `GitBlobNoneFilter`;
- `GitBlobLimitFilter`;
- `GitTreeDepthFilter`;
- `GitSparsePathFilter`;
- `GitObjectFilterParseResult`;
- `GitObjectFilterDiagnostics`;

Start with:

```text
GitObjectFilter.NONE
GitObjectFilter.BLOB_NONE
```

Parser behavior:

- accept exactly `blob:none` for the first phase;
- reject unknown filter kinds;
- reject malformed filter values;
- reject duplicate filters unless the protocol layer chooses one explicitly;
- preserve the raw value for diagnostics;
- avoid echoing hidden object or ref details in user-visible failures.

Object selection behavior for `blob:none`:

- include commits selected by wants and negotiation;
- include annotated tag objects and peeled targets when selected;
- include all tree objects needed to describe selected commits;
- omit blob contents reached only through trees;
- include explicitly wanted blobs only when direct blob wants are allowed by
  policy and access checks;
- do not include delta bases for omitted blobs;
- do not include blobs solely because a tree references them.

## Future Filter Types

Reserve model space for later filters.

`blob:limit=<n>`:

- include blobs with size at or below the limit;
- omit larger blobs;
- requires reliable blob size metadata before pack selection.

Tree-depth filters:

- include trees only to a requested depth;
- omit deeper trees and blobs;
- more complex for path resolution and should not be first.

Sparse path filters:

- include only objects under requested paths;
- requires path-aware traversal and careful access policy interaction;
- should be treated as a separate follow-up after basic object filters.

The filter parser should reject unsupported future filters until their selection
rules and tests exist.

## Shallow Model

Introduce a request model for shallow operations.

Candidate value objects:

- `GitShallowRequest`;
- `GitDepthRequest`;
- `GitDeepenSinceRequest`;
- `GitDeepenNotRequest`;
- `GitClientShallowState`;
- `GitShallowBoundary`;
- `GitShallowSelectionResult`;
- `GitShallowDiagnostics`.

First supported input:

- `deepen <depth>` where depth is a positive integer.

Later supported input:

- `deepen-since <timestamp>`;
- `deepen-not <ref or revision>`;
- client `shallow <object-id>` state;
- `deepen-relative`.

Depth semantics for the first phase:

- depth 1 includes wanted tip commits but excludes their parents;
- depth N includes commits up to N commit levels from each wanted tip;
- merge parents count as the next level from a commit;
- commits at the boundary are reported as shallow when their parents are
  excluded;
- tree and blob closure for included commits is still selected unless filters
  omit it.

## Protocol v2 Request Handling

The upload-pack request parser should normalize protocol v2 fetch arguments into
typed inputs.

Relevant request inputs:

- `want <object-id>`;
- `have <object-id>`;
- `done`;
- `thin-pack`;
- `ofs-delta`;
- `side-band-64k`;
- `filter <filter-spec>`;
- `deepen <depth>`;
- `deepen-since <timestamp>`;
- `deepen-not <ref>`;
- `shallow <object-id>`;
- `want-ref <refname>` if supported later.

The parser should not decide object visibility or graph correctness. It should
return a structured request plus typed parse errors.

Unsupported but recognized inputs:

- return an unsupported-capability or unsupported-option failure;
- do not ignore them silently;
- do not advertise the corresponding capability until supported.

## Server-Side Fetch Flow

Native upload-pack should apply filters and shallow state in a defined order.

Server flow:

1. Parse protocol request.
2. Validate advertised and requested capabilities.
3. Resolve wants through object lookup and visible refs.
4. Validate access policy for wants.
5. Normalize filter and shallow request.
6. Compute accepted haves.
7. Compute included commits subject to shallow rules.
8. Compute omitted history boundaries.
9. Compute object closure for included commits.
10. Apply object filter to closure.
11. Validate pack closure with delta/thin-pack policy.
12. Emit shallow/unshallow response information.
13. Build and stream pack.
14. Record stats and diagnostics.

Filters should apply after the commit/tree closure is known, not before commit
selection. Shallow rules change which commits are included; filters change which
objects under included commits are sent.

## Object Closure Rules

Without filters:

- selected commits include their root trees;
- root trees include nested trees and blobs;
- annotated tags include tag objects and targets;
- all selected objects must be present in the response pack or already validly
  available to the receiver through negotiated rules.

With `blob:none`:

- selected commits are included;
- selected tree objects are included;
- selected tag objects are included;
- blobs reached from trees are omitted;
- explicitly wanted blobs can be included only when direct blob wants are
  permitted;
- omitted blobs are recorded as promised or filtered depending on caller needs.

With shallow depth:

- commits beyond the boundary are not included;
- parents of boundary commits are not traversed for commit history;
- tree closure for included commits still applies;
- if a parent commit object is excluded, no objects reachable only through that
  parent are included unless also reachable from included commits.

With both `blob:none` and shallow:

- commit selection is shallow-limited first;
- tree closure is computed for included commits;
- blob filter removes tree-reached blobs;
- shallow boundaries are reported independently from filtered blobs.

## Direct Blob Wants

Remote single-file fetch may need to fetch the requested blob after resolving the
path from a filtered commit/tree pack.

Direct blob wants should be controlled by policy:

- allow if the blob is reachable from a visible requested ref or commit;
- reject if the blob is not reachable from any visible allowed object;
- reject if the server policy disables arbitrary object-id wants;
- return a typed unsupported result if the remote server refuses direct blob
  wants.

Server-side Orion should avoid advertising behavior that implies arbitrary object
exfiltration. A blob wanted by object id must pass reachability and visibility
checks.

Client-side Orion should handle remote differences:

- strict mode fails if direct blob want is rejected;
- compatibility mode may retry with a bounded shallow no-filter fetch;
- fallback result must record that filtering failed or direct blob want failed.

## Pack Closure Validation

Before streaming a filtered or shallow response, validate the selected objects.

Validation rules:

- no object outside the selected set is written;
- no filtered blob is accidentally included;
- all included commits have their root tree included unless a future tree filter
  explicitly permits omission;
- all included trees needed by selected commits are included;
- every delta base is included or valid under thin-pack rules;
- no delta is generated against a filtered-out blob unless the base is a proven
  external base and thin-pack policy allows it;
- shallow boundary commits are marked before pack streaming starts;
- hidden-ref-only objects are not used as proof for haves or omitted bases.

Pack builder inputs should include selection reasons so diagnostics can explain
why an object was included or omitted.

## Delta And Thin-Pack Interaction

Filtering and shallow state constrain delta generation.

Rules:

- do not delta-compress an included object against a base omitted by filter
  unless thin-pack policy proves the receiver has the base;
- do not use a shallow-excluded ancestor as an external base unless accepted
  haves prove availability under shallow rules;
- prefer whole-object output when filter or shallow proof is ambiguous;
- disable thin-pack for the first shallow implementation if proof is incomplete;
- record fallback reasons in pack planning diagnostics.

The delta planner should receive:

- object filter;
- shallow boundary set;
- accepted haves;
- visible reachability proof scope;
- allowed external bases.

## Reachability Integration

Reachability service needs filtered and shallow query options.

Candidate additions:

- `GitObjectClosureOptions.filter`;
- `GitObjectClosureOptions.shallowRequest`;
- `GitObjectClosureOptions.clientShallowState`;
- `GitObjectSelectionReason.FILTERED_OUT`;
- `GitObjectSelectionReason.SHALLOW_BOUNDARY`;
- `GitObjectSelectionReason.EXPLICIT_WANT`;
- `GitObjectSelectionReason.DIRECT_BLOB_WANT`;

The reachability service should return:

- included object ids;
- omitted object ids when useful for diagnostics, bounded by limits;
- shallow boundary commits;
- unshallow commits;
- accepted and rejected haves;
- fallback diagnostics when acceleration indexes are unavailable.

Acceleration indexes can help, but canonical traversal must remain available.

## Access Policy

Access checks run before object selection is exposed to protocol output.

Rules:

- wants must be reachable from visible refs or otherwise authorized;
- direct blob wants require visible reachability proof;
- hidden refs must not appear in errors or diagnostics returned to the client;
- filter omission must not reveal that a hidden object exists;
- shallow boundary computation must not report commits reachable only from hidden
  refs unless they are also visible through the request;
- accepted haves must not be used to infer access to hidden refs.

If access policy cannot determine visibility within limits, fail the request
before pack streaming.

## Client-Side Single-File Fetch

Orion's native remote single-file fetch should use this sequence when possible:

1. Connect to upload-pack and request protocol v2.
2. Run `ls-refs` for the requested ref or ref prefix.
3. Fetch the resolved commit with `filter blob:none`.
4. Parse returned commit and tree objects into a transient store.
5. Resolve the requested path to a blob id and mode.
6. Fetch the blob id directly without `blob:none`, or with an explicit direct
   blob mode if supported.
7. Return bytes plus commit id, tree id, blob id, mode, and diagnostics.

If the remote does not support filter:

- strict mode returns a typed unsupported result;
- compatibility mode can fetch a bounded shallow pack;
- fallback must respect configured object count, byte count, and time limits.

If path resolution needs trees omitted by a future tree filter, retry without
that tree filter or fail with a typed unsupported-filter result.

## Client Fallback Modes

Define explicit fallback modes.

`STRICT_FILTERED`:

- require protocol v2;
- require `filter`;
- require `blob:none`;
- require direct blob retrieval;
- no shallow no-filter fallback.

`BOUNDED_COMPATIBILITY`:

- prefer filtered path;
- if unavailable, allow shallow no-filter fetch within limits;
- if direct blob want fails, allow one bounded retry;
- record fallback reason.

`DISABLED`:

- do not perform remote single-file fetch.

Every result should include:

- negotiated protocol version;
- filter used or not used;
- shallow depth used or not used;
- fallback reason;
- pack bytes received;
- object count received;
- whether the result came from an exact direct blob fetch or a larger pack.

## S3 Backend Considerations

Server-side S3 repositories should avoid full repository scans.

For filtered fetch:

- tree objects still need to be loaded;
- blobs can be skipped once tree entries identify them;
- object size metadata helps `blob:limit` later;
- range reads should be batched for tree traversal.

For shallow fetch:

- commit parent traversal can use commit projection or reachability indexes;
- missing or stale indexes fall back to bounded canonical traversal;
- S3 read diagnostics should record index hits and range read counts.

The first S3 implementation can keep filter and shallow support disabled until
local backend behavior is stable.

## Error Model

Use typed failures:

- filter capability not advertised;
- unsupported filter;
- malformed filter;
- shallow capability not advertised;
- malformed deepen value;
- unsupported deepen mode;
- wanted object missing;
- wanted object unauthorized;
- direct blob want not allowed;
- path blob not reachable from visible commit;
- closure traversal limit exceeded;
- shallow boundary computation failed;
- filtered pack validation failed;
- fallback limit exceeded;
- client disconnected during response;
- pack build failure.

Protocol mapping:

- before pack data starts, send Git protocol `ERR` where appropriate;
- after side-band mode starts, send fatal errors on band 3;
- for client-side remote fetch, map remote protocol errors to typed
  `GitRemoteFetchException` variants or equivalent local errors.

Do not include credentials, hidden refs, hidden object paths, or object contents
in errors.

## Observability

Record metrics:

- upload-pack requests with filter;
- upload-pack requests with shallow depth;
- unsupported filter count;
- unsupported shallow mode count;
- filtered objects omitted by type;
- blobs omitted by `blob:none`;
- shallow boundary commit count;
- unshallow commit count;
- canonical traversal fallback count;
- acceleration index hit/miss count;
- direct blob want count;
- single-file fetch fallback count;
- received pack bytes for single-file fetch;
- sent pack bytes for filtered and shallow server responses.

Structured diagnostics should include:

- repository id;
- request id;
- protocol version;
- normalized filter;
- normalized shallow request;
- selected object count by type;
- omitted object count by type, bounded;
- pack build mode;
- fallback reason.

## Phased Plan

Phase 1: Filter parser and model.

- Add typed filter model.
- Parse `blob:none`.
- Reject unsupported filters with typed errors.
- Add unit tests for valid, malformed, duplicate, and unsupported filters.

Phase 2: Shallow request model.

- Add typed shallow request and client shallow state models.
- Parse `deepen <depth>`.
- Reject zero, negative, malformed, and unsupported deepen inputs.

Phase 3: Filtered closure selection.

- Extend object closure options with `blob:none`.
- Include commits, tags, and trees.
- Omit tree-reached blobs.
- Add tests for linear history, nested trees, annotated tags, and explicit blob
  wants.

Phase 4: Shallow boundary selection.

- Compute depth-based included commits and boundary commits.
- Preserve tree closure for included commits.
- Add tests for depth 1, depth 2, merge commits, and multiple wants.

Phase 5: Server capability gating.

- Advertise `filter` only after phases 1 and 3 are wired.
- Advertise `shallow` only after phases 2 and 4 are wired.
- Add tests proving unsupported capabilities are not advertised.

Phase 6: Upload-pack response integration.

- Emit shallow/unshallow response information.
- Pass filtered/shallow object set to pack builder.
- Validate pack closure before streaming.
- Record stats and diagnostics.

Phase 7: Direct blob wants.

- Add policy for direct object-id blob wants.
- Require visible reachability proof.
- Add tests for allowed reachable blob, unauthorized blob, missing blob, and
  hidden-ref-only blob.

Phase 8: Client filtered single-file fetch.

- Use protocol v2 `ls-refs`.
- Fetch commit/tree data with `filter blob:none`.
- Resolve path locally.
- Fetch direct blob.
- Return detailed diagnostics.

Phase 9: Bounded client fallback.

- Add shallow no-filter fallback for remotes without filter support.
- Enforce pack byte, object count, and time limits.
- Record fallback reason in result.

Phase 10: Delta and thin-pack integration.

- Pass filter and shallow state to delta planner.
- Disable unsafe external bases.
- Add tests for filtered-out delta bases and shallow-excluded bases.

Phase 11: S3 backend integration.

- Enable filter/shallow behind feature flags for S3 repositories.
- Use index-assisted traversal where available.
- Add local-vs-S3 parity tests for object selection.

Phase 12: Additional filters.

- Add `blob:limit=<n>` after blob size metadata is reliable.
- Consider tree-depth and sparse path filters only after access policy and path
  traversal rules are explicit.

## Verification

Filter parser:

- accepts `blob:none`;
- rejects unknown filter kind;
- rejects malformed value;
- rejects duplicate filters according to parser policy;
- does not advertise `filter` when parser or selection is disabled.

Filtered selection:

- includes commit objects;
- includes root and nested tree objects;
- omits blobs reached only through trees;
- includes annotated tag objects and targets correctly;
- includes explicitly wanted reachable blob when policy allows direct blob wants;
- rejects hidden-ref-only direct blob wants.

Shallow selection:

- depth 1 includes tips and marks them shallow when parents exist;
- depth 2 includes parents and marks boundary parents shallow;
- merge commit depth includes both parents at the next level;
- multiple wants share commits without duplicates;
- shallow boundaries are deterministic.

Upload-pack:

- filtered response pack contains no omitted blobs;
- shallow response reports boundary commits;
- unsupported filters produce typed protocol errors;
- unsupported shallow modes are not advertised;
- side-band fatal errors do not corrupt pack data;
- upload stats record filter and shallow usage.

Single-file client:

- filtered fetch retrieves commit and tree without blob contents;
- path resolution finds nested file blob id and mode;
- direct blob fetch returns only the requested blob when remote allows it;
- strict mode fails when remote lacks filtering;
- compatibility mode uses bounded shallow fallback;
- fallback refuses packs above configured limits;
- diagnostics record negotiated protocol and fallback reason.

Delta and thin-pack:

- delta planner does not use filtered-out blob as an internal base;
- thin-pack external base proof respects shallow state;
- ambiguous proof falls back to whole-object output.

Access:

- hidden refs are not advertised or named in errors;
- unauthorized wants fail before pack streaming;
- direct blob wants require visible reachability proof;
- shallow boundary output does not reveal hidden-only commits.

S3:

- local and S3 object selection match for the same fixtures;
- stale acceleration indexes fall back or fail according to policy;
- range read counts are bounded for filtered tree traversal.

## Rollout

Keep `filter` and `shallow` unadvertised in native upload-pack until each feature
passes object-selection and protocol-response tests.

Enable client-side filtered single-file fetch against scripted protocol fixtures
before testing public Git servers.

Add compatibility fallback only after strict filtered mode is stable.

Enable server-side `blob:none` before shallow fetch because it is needed for
efficient single-file reads and has a smaller graph boundary surface.

Enable shallow fetch only after depth boundaries, existing client shallow state,
and delta/thin-pack interactions are covered.

Keep S3 filter/shallow serving behind feature flags until local backend parity is
verified.

## Open Questions

Should direct blob wants be enabled by default for native Orion upload-pack, or
only for authenticated/internal clients?

Should server-side filtered responses record promised objects explicitly, or is
omission diagnostics enough for the first phase?

Should compatibility fallback use `deepen 1` first or a configurable depth based
on expected tree/path size?

Should unsupported filters fail the entire request or ignore only if the client
marks them optional? Git protocol normally treats the filter as part of the
request, so failing is safer.

How should filtered fetch interact with future sparse path ACL rules?

Should shallow fetch be disabled when thin-pack is enabled until external-base
proof is fully implemented?

## Acceptance Criteria

Native upload-pack can parse, validate, and serve `filter blob:none` without
JGit, and it does not advertise `filter` before this is true.

Native upload-pack can parse depth-based shallow requests, compute deterministic
boundary commits, and return correct shallow response metadata.

Filtered and shallow object selection produces packs that are valid, bounded,
and free of accidentally included filtered objects.

Remote single-file fetch can retrieve one file through protocol v2 filtered
fetch without JGit, with a bounded and visible fallback path.

Access policy, hidden refs, delta bases, and thin-pack behavior remain correct
under filtered and shallow requests.

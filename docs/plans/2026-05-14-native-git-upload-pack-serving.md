# Native Git Upload-Pack Serving

## Goal

Add JGit-free upload-pack serving so Orion can handle clone and fetch requests
from Git clients using the native repository backend.

This plan covers server-side protocol behavior for `git-upload-pack`: advertising
refs, parsing client wants and haves, enforcing fetch access checks, selecting
objects, building response packs, and reporting upload statistics without
delegating to JGit `UploadPack`.

## Current State

`GitInternalService` parses the initial Git command, opens the repository, checks
repository read access, and calls `GitRepository.upload()`.

`JGitRepository.upload()` currently creates JGit `UploadPack`, installs pre/post
upload hooks, delegates all negotiation and pack writing to JGit, and translates
JGit pack statistics into `GitUploadStats`.

The native transport plans cover socket and SSH command wiring, not upload-pack
protocol semantics. The native repository backend plan states that upload serving
should eventually use refs, object lookup, commit graph data, and the pack
builder, but does not define the server-side protocol flow.

The native protocol client primitives plan is client-focused. Some primitives,
such as pkt-line and side-band codecs, can be shared, but server upload-pack
needs its own request parser and response state machine.

## Non-Goals

Do not implement receive-pack in this plan.

Do not implement object storage, pack parsing, pack index, or ref storage here.
This layer consumes those repository services.

Do not replace the existing JGit-backed upload path immediately. Keep native
upload-pack behind a backend capability or feature flag until parity tests pass.

Do not implement every optional Git upload-pack capability in the first phase.
Start with a minimal clone/fetch path, then add filters, shallow fetch, and
advanced negotiation features deliberately.

Do not shell out to the `git` executable in production code.

Do not depend on JGit in production code. Tests may compare output against Git
CLI or JGit fixtures.

## Protocol Versions

Support protocol v2 as the first explicit target because it has structured
commands and cleaner capability negotiation.

Also define a compatibility path for protocol v0/v1 because many clients and
existing Orion tests exercise classic upload-pack behavior. The first production
native rollout can choose one of two policies:

- protocol v2 only, with a clear unsupported protocol error for v0/v1;
- minimal v0/v1 advertisement and fetch support for existing clients.

The choice should be made by tests and client compatibility requirements, not by
mixing both implementations accidentally.

## Capability Model

Advertise only capabilities the native server implements.

Initial candidates:

- `version=2` service negotiation where applicable;
- `ls-refs`;
- `fetch`;
- `agent`;
- `object-format=sha1`;
- `side-band-64k`;
- `ofs-delta`;
- `thin-pack` only after thin-pack behavior is implemented;
- `filter` only after filtered fetch is implemented;
- `shallow` only after shallow fetch is implemented.

Unknown client capabilities should be ignored only when Git protocol allows it.
Unsupported requested features should produce typed protocol failures or clear
capability omission.

## Ref Advertisement

Build ref advertisement from `GitRefStore`:

- advertise branches and tags;
- advertise `HEAD` symbolic target when available;
- include peeled annotated tags when the object model can resolve them;
- omit internal Orion refs unless explicitly configured;
- maintain stable ordering for deterministic tests;
- handle unborn `HEAD` cleanly for empty repositories.

For protocol v2, implement `ls-refs` with:

- `ref-prefix`;
- `symrefs`;
- `peel`;
- `unborn` when supported.

For v0/v1 compatibility, generate the initial advertised refs packet sequence
with the selected capabilities on the first ref line.

## Request Parsing

Add upload-pack request value objects:

- `GitUploadPackRequest`;
- `GitWantedObject`;
- `GitHaveObject`;
- `GitUploadNegotiationState`;
- `GitUploadCapabilities`;
- `GitUploadResponsePlan`.

Parse:

- wanted object ids;
- have object ids;
- `done`;
- `deepen` and shallow options only after shallow support exists;
- filter options only after filter support exists;
- side-band preference;
- thin-pack preference;
- client agent where provided.

Reject malformed object ids, unsupported object format, invalid packet sequences,
and requests that exceed configured limits.

## Fetch Access Checks

Preserve Orion's existing fetch access semantics.

Today `JGitRepository` invokes `GitFetchAccessRequest` with wanted object ids and
a branch resolver. The native upload-pack server should do the same before it
sends pack data:

1. collect wanted object ids;
2. resolve wanted objects to branch names through native ref and commit graph
   data;
3. call the configured fetch access check;
4. reject unauthorized wants before object enumeration or pack generation.

Access failures should produce a protocol-safe error response without leaking
hidden branch names.

## Object Negotiation

Start with a conservative negotiation algorithm:

1. validate wanted object ids exist;
2. collect client haves;
3. determine which wanted objects are already satisfied by haves;
4. compute reachable object closure from wanted tips;
5. subtract objects reachable from haves;
6. build a pack from the remaining objects.

The first implementation can be less optimal than JGit as long as it is correct
and bounded. Performance can improve after commit graph and bitmap-like indexes
exist.

The negotiation layer should be independent from pack writing so it can be tested
with small synthetic graphs.

## Object Enumeration

Use repository services:

- ref store for advertised tips;
- commit graph or object traversal for reachability;
- object lookup for object existence and type;
- tree traversal for commit closure;
- tag peeling for tag requests;
- pack builder for output.

Enumeration must include all objects required by Git clients:

- commits;
- trees reachable from selected commits;
- blobs referenced by those trees;
- annotated tag objects and their targets where requested;
- objects needed by delta bases when thin-pack is disabled.

When thin-pack is enabled later, base omission must be explicit and only when the
client advertised support.

## Pack Response

Build response packs through the native pack builder.

Response handling:

- send ACK/NAK negotiation responses according to protocol version and selected
  mode;
- stream pack bytes without loading large packs fully in memory;
- use side-band-64k when negotiated;
- send progress messages only through side-band progress when enabled;
- record object count and byte count for `GitUploadStats`;
- call `afterUpload` exactly once for successful pack sends.

Pack generation should allow deterministic test mode with fixed ordering and
compression settings.

## Filters and Shallow Fetch

Add after the basic clone/fetch path works.

Filter support:

- start with `filter blob:none`;
- ensure omitted blobs are truly omitted from the pack;
- expose filter use in upload stats/diagnostics;
- reject unsupported filter expressions clearly.

Shallow support:

- parse deepen requests;
- compute shallow boundaries;
- advertise shallow/unshallow lines correctly;
- ensure history truncation still sends required trees and blobs.

These features affect correctness and access control, so they should not be
enabled by advertising capabilities before full tests exist.

## Error Handling

Use typed failures internally:

- malformed request;
- unsupported protocol version;
- unsupported capability;
- unauthorized wants;
- missing wanted object;
- object traversal failure;
- pack build failure;
- client disconnected;
- timeout;
- response write failure.

Map failures to Git protocol responses:

- service-level `ERR` packets before pack data starts;
- side-band fatal errors after side-band response starts;
- clean connection close for client disconnects where appropriate.

Do not include credentials, hidden branch names, or object content in error
messages.

## Phased Plan

Phase 1: Protocol parser and fixtures.

Implement upload-pack request parsing for protocol v2 `ls-refs` and minimal
`fetch` over scripted streams. Add malformed packet and unsupported capability
fixtures.

Phase 2: Ref advertisement.

Serve `ls-refs` from `GitRefStore`, including branches, tags, `HEAD` symref, and
peeled tags where available.

Phase 3: Basic want validation and access checks.

Parse wants, validate object ids through object lookup, resolve wanted branches,
and enforce `GitFetchAccessRequest`.

Phase 4: Object closure enumeration.

Compute the object set needed for simple fetches from commit tips. Cover commits,
trees, blobs, and annotated tags.

Phase 5: Pack response.

Build and stream a no-delta pack for selected objects. Support side-band-64k and
upload statistics.

Phase 6: Protocol v0/v1 compatibility decision.

Either implement minimal v0/v1 clone/fetch or add explicit unsupported behavior
and tests documenting the rollout requirement.

Phase 7: Negotiation improvements.

Subtract objects reachable from client haves. Add multi-round negotiation tests
with common ancestors and missing haves.

Phase 8: Filtered fetch.

Implement `filter blob:none` and reject unsupported filters. Verify omitted blob
behavior with real Git clients where practical.

Phase 9: Shallow fetch.

Implement deepen handling and shallow boundary responses.

Phase 10: Native repository integration.

Wire native upload-pack serving behind `GitRepository.upload()` for the native
repository backend and run parity tests against JGit-backed fixtures.

## Open Questions

Should native upload-pack initially support protocol v0/v1 to keep current Git
client scenarios working, or require protocol v2 during the native backend pilot?

Should object enumeration use the commit information projection from the start,
or walk canonical objects first and optimize later?

How should upload statistics count reused objects when the native pack builder
does not yet reuse existing pack deltas?

Should thin-pack support wait until delta reconstruction and pack building with
explicit bases are fully stable?

What is the minimum real Git client end-to-end suite required before enabling
native upload-pack outside tests?

## Verification

Cover at least these cases:

- production native upload-pack code has no JGit dependency;
- empty repository advertises unborn `HEAD` or no refs according to protocol
  rules;
- `ls-refs` returns branches, tags, `HEAD` symref, and peeled annotated tags;
- malformed requests fail with protocol-safe errors;
- unauthorized wanted object is rejected before pack generation;
- missing wanted object returns a typed protocol failure;
- simple clone from one commit receives commit, tree, and blob objects;
- fetch with existing haves omits objects reachable from haves;
- side-band-64k separates pack, progress, and fatal errors;
- upload stats report object count and byte count for successful sends;
- pack bytes produced by native upload can be parsed by Git CLI or JGit;
- protocol v0/v1 behavior is either supported by tests or explicitly rejected by
  tests;
- `filter blob:none` omits blobs only after the capability is advertised;
- shallow fetch responses include correct shallow boundary data after support is
  added;
- native repository backend can serve a simple fetch through `GitRepository.upload`;
- fetch access branch restrictions match the existing JGit-backed behavior.

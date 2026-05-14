# Git Pack Delta Generation and Thin-Pack Support

## Goal

Add a JGit-free outbound delta generation layer for Orion-built Git pack files.

This plan covers:

- creating Git delta instruction streams from canonical object content;
- selecting safe and useful delta bases;
- writing `REF_DELTA` and `OFS_DELTA` pack entries;
- deciding when to fall back to whole-object entries;
- supporting thin-pack output only when the peer already has the omitted bases;
- preparing later reuse of existing pack entries and existing deltas.

The goal is not to make delta compression mandatory. The native pack builder
must remain correct with no deltas. Delta generation is an optimization and a
wire-protocol capability that must be guarded by explicit policy.

## Current State

`docs/plans/2026-05-14-git-pack-build-from-changes.md` defines deterministic
no-delta pack creation first. It mentions delta support as a follow-up but does
not define the delta instruction generator, base selection, thin-pack policy, or
pack-entry encoding details.

`docs/plans/2026-05-14-git-delta-resolution-object-reconstruction.md` defines
how to consume incoming `OFS_DELTA` and `REF_DELTA` entries and reconstruct full
objects. That work applies existing deltas. It does not choose delta bases or
generate outbound deltas.

`docs/plans/2026-05-14-native-git-upload-pack-serving.md` and
`docs/plans/2026-05-14-native-git-wire-protocol-core.md` list `ofs-delta` and
`thin-pack` as protocol capabilities. Upload-pack should not advertise thin-pack
until Orion can prove the omitted bases are available to the client.

`docs/plans/2026-05-14-native-remote-git-single-file-push.md` and
`docs/plans/2026-05-14-native-git-save-files-write-path.md` can start with
no-delta packs. Later they can enable deltas behind the same pack builder policy.

`docs/plans/2026-05-14-git-repository-maintenance-gc.md` describes future
repacking and duplicate-object compaction. That later work needs the same delta
generation primitives, but it can use a different policy from interactive
upload-pack serving.

## Non-Goals

Do not block native pack writing on delta generation. Whole-object pack output
remains the correctness baseline.

Do not implement incoming delta reconstruction here. This plan can reuse the
delta instruction parser and resolver from the reconstruction plan for tests,
but this plan owns outbound generation only.

Do not implement raw pack parsing here. The parser and index plans continue to
own reading existing pack bytes.

Do not require byte-for-byte equality with JGit in the first implementation.
Canonical object equality, valid Git pack structure, deterministic local output,
and Git interoperability are enough first.

Do not advertise `thin-pack` or `ofs-delta` until the relevant phase is complete
and covered by tests.

Do not generate cross-type deltas. Git permits deltas to inherit the base object
type, so a delta target must have the same final object type as its base.

Do not omit bases for receive-pack input from clients unless a separate receive
policy explicitly allows thin incoming packs and can resolve external bases.

## Design Principles

Delta generation must be optional and reversible. If a delta decision is unsafe,
too large, too expensive, or unsupported by the negotiated protocol, the pack
builder writes a whole-object entry.

The pack writer should encode decisions, not make all policy decisions itself.
Base selection, candidate scoring, thin-pack permissions, and reuse policy should
live in explicit strategy objects.

The output must be deterministic for the same input, repository state, and
settings. Candidate iteration order, tie-breaking, compression settings, and
pack entry ordering must not depend on hash map iteration order.

The first generated deltas can be simple. A small, correct delta is more useful
than a complex compressor that is difficult to bound or audit.

Every generated delta must be validated by applying it back to its base during
tests, and optionally during debug or strict validation mode.

## Terminology

Whole-object entry:

- a `COMMIT`, `TREE`, `BLOB`, or `TAG` pack entry containing compressed object
  content directly.

Delta entry:

- a `REF_DELTA` or `OFS_DELTA` pack entry containing a delta instruction stream;
- the final object type is inherited from the base object.

Internal base:

- a base object that is included earlier in the same output pack.

External base:

- a base object that is not included in the output pack;
- valid only for thin-pack output and only when the receiver already has it.

Thin pack:

- a pack that contains delta objects whose bases are outside the pack.

Delta chain depth:

- number of delta steps from a target object back to a whole-object base.

## Data Model

Add outbound delta-facing value objects in the native Git engine.

`GitDeltaInstruction`:

- instruction kind: insert or copy;
- source offset for copy instructions;
- source length for copy instructions;
- inserted bytes for insert instructions.

`GitDeltaProgram`:

- source size;
- result size;
- ordered instruction list;
- encoded instruction bytes;
- estimated encoded size.

`GitDeltaBase`:

- base object id;
- object type;
- uncompressed object size;
- optional pack id and pack offset;
- whether the base is included in the current output pack;
- whether the base may be omitted as a thin-pack external base.

`GitDeltaCandidate`:

- target object id;
- base object id;
- object type;
- candidate score;
- estimated whole-object compressed size;
- estimated delta compressed size;
- delta chain depth if selected.

`GitDeltaDecision`:

- target object;
- whole-object output or delta output;
- selected base when delta output is chosen;
- delta program;
- delta reference kind: `REF_DELTA` or `OFS_DELTA`;
- fallback reason when whole-object output is chosen.

`GitPackDeltaPlan`:

- ordered output entries;
- map of target object id to delta decision;
- required external base ids for thin-pack output;
- capabilities required by the plan;
- validation warnings.

`GitDeltaGenerationSettings`:

- enabled flag;
- maximum object size for delta generation;
- maximum base candidates per object;
- maximum delta chain depth;
- maximum delta instruction count;
- maximum delta bytes before compression;
- minimum savings ratio;
- timeout or operation budget;
- whether to allow external bases.

## Delta Instruction Format

Git delta payloads start with two variable-length integers:

1. source size;
2. result size.

The remaining payload is a sequence of copy and insert instructions.

Insert instruction:

- opcode high bit is unset;
- opcode value is the number of literal bytes to insert;
- opcode value zero is reserved and must never be emitted;
- one instruction can insert at most 127 bytes.

Copy instruction:

- opcode high bit is set;
- opcode bits select which offset and size bytes follow;
- omitted offset bytes are zero;
- omitted size bytes mean default size 0x10000;
- copy range must be fully inside the source bytes.

The generator must never emit a reserved opcode. It must split large inserts and
copies into valid instruction sizes.

## Delta Generation Algorithm

Start with a conservative byte-array generator that is correct for all object
types but mainly useful for blobs.

Phase-one algorithm:

1. Build a rolling index over the base bytes using fixed-size windows.
2. Scan the target bytes from left to right.
3. At each target offset, find candidate base matches from the rolling index.
4. Extend the best match byte-by-byte while it remains equal.
5. Emit a copy instruction when the match is long enough to beat an insert.
6. Otherwise buffer target bytes into insert instructions.
7. Flush insert buffers before each copy and at the end.
8. Encode source size, result size, and instructions.
9. Apply the generated program to the base bytes in validation mode.

The first implementation can use a simple hash table keyed by 16-byte windows.
The window size should be configurable for tests but default to a practical
value for source text and tree-like data.

The generator should favor correctness over optimality:

- if the candidate search budget is exhausted, emit the rest as inserts;
- if the delta program is larger than the target, fall back to whole-object;
- if memory limits are exceeded, fall back to whole-object;
- if validation fails, treat it as a bug in tests and a hard failure in strict
  runtime validation.

## Base Selection

Base selection is separate from instruction generation.

The selector receives:

- target object metadata;
- target canonical object content or a readable content handle;
- candidate bases from the same pack request;
- candidate bases from repository object storage;
- negotiated peer haves when serving fetch;
- operation settings.

Candidate bases must satisfy:

- same object type as the target;
- base object id differs from target object id;
- base content is available or can be loaded within budget;
- selecting the base will not exceed chain-depth limits;
- external base use is allowed only by thin-pack policy.

Initial scoring should be simple and deterministic:

1. prefer same path history when available;
2. prefer similar object sizes;
3. prefer recent versions over older versions;
4. prefer bases already included in the output pack;
5. prefer bases that avoid external thin-pack requirements unless thin-pack is
   explicitly desired;
6. tie-break by object id lexical order.

The selector should return a bounded list of candidates per target. The delta
generator can then try candidates in score order and choose the smallest safe
output.

## Object-Type Policy

Blob objects:

- primary target for generated deltas;
- use same path and similar-size hints when available;
- allow large-file limits and binary-file limits to avoid expensive scans.

Tree objects:

- can be delta-compressed later, but phase one may write whole-object trees;
- tree content is usually small enough that deltas may not pay off;
- generated deltas for trees must preserve canonical tree bytes exactly.

Commit objects:

- phase one should write commits as whole objects;
- commit deltas can be considered later for repack jobs, not interactive writes;
- commit content is usually small and frequently needed for traversal.

Tag objects:

- write whole-object tags first;
- tag deltas are low priority.

## Pack Entry Encoding

The existing pack builder writes whole-object entries. Extend it to accept a
planned output entry:

- whole object: type is commit, tree, blob, or tag;
- ref delta: type is `REF_DELTA`, followed by 20-byte base object id;
- offset delta: type is `OFS_DELTA`, followed by encoded negative base offset.

The compressed payload for a delta entry is the encoded delta program, not the
final object content.

The pack checksum is still computed over all bytes before the trailer. The pack
entry count includes delta entries exactly like whole-object entries.

## REF_DELTA

`REF_DELTA` references the base by object id. It is useful when:

- the base is external to the pack;
- output offsets are not yet known;
- the pack writer is in an early phase and wants simpler correctness.

Initial delta output should use `REF_DELTA` because it is easier to test and
does not require offset backpatching.

For non-thin packs, a `REF_DELTA` base should still be included in the pack unless
the receiver is explicitly known to have it and thin-pack is enabled.

## OFS_DELTA

`OFS_DELTA` references a base by a negative offset from the delta entry.

It should be enabled only after pack ordering and entry-offset tracking are
stable.

Rules:

- the base entry must appear earlier in the same pack;
- the writer must know the byte offset of both entries;
- the encoded offset must decode to the exact base entry offset;
- external bases cannot use `OFS_DELTA`;
- deterministic pack ordering must keep bases before their delta children.

The pack planner can choose `OFS_DELTA` for internal bases when the peer
advertised `ofs-delta`. Otherwise it must use whole-object output or `REF_DELTA`
when valid.

## Thin-Pack Policy

Thin-pack output is valid only when all omitted bases are guaranteed to be
available to the receiver.

For upload-pack:

- the client must advertise `thin-pack`;
- the omitted base must be reachable from at least one acknowledged have;
- the omitted base must not be hidden by access policy;
- the omitted base must not require exposing an object reachable only from hidden
  refs;
- the pack response must not include external bases that the negotiation did not
  prove.

For remote push from Orion to another Git server:

- start with non-thin packs;
- enable thin outgoing push only after the client primitive layer tracks remote
  haves accurately;
- fall back to full packs when the remote does not advertise thin-pack support.

For receive-pack:

- do not accept thin incoming packs in the first native receive implementation;
- later, allow them only when all external bases resolve from the target
  repository and access policy permits the update.

The `GitPackDeltaPlan` must expose required external base ids. Upload-pack can
log and validate them before streaming begins.

## Negotiated Capabilities

The pack planner receives negotiated capabilities rather than reading protocol
state directly.

Inputs:

- peer supports `ofs-delta`;
- peer supports `thin-pack`;
- peer supports side-band or side-band-64k;
- object format is sha1 for current repository support;
- optional future filters such as `blob:none`;
- shallow/deepen state.

Outputs:

- capabilities required by the chosen pack plan;
- whether output uses `OFS_DELTA`;
- whether output uses `REF_DELTA`;
- whether output is thin;
- whether any requested optimization was disabled and why.

Upload-pack should advertise `ofs-delta` only after Orion can write or pass
through offset deltas correctly for served packs. It should advertise `thin-pack`
only after thin-pack validation exists.

## Pack Ordering

Pack ordering must satisfy both deterministic output and delta constraints.

Whole-object baseline order:

1. commits;
2. tags;
3. trees;
4. blobs;
5. lexical object id within type.

Delta-enabled order:

1. roots that are written as whole-object entries;
2. internal bases before their delta children;
3. children ordered deterministically by object id or path hint;
4. objects without delta decisions in the whole-object baseline order.

The planner should detect cycles before writing. A cycle in delta decisions is a
planner bug and must fail before any pack bytes are emitted.

For `OFS_DELTA`, the writer should not need to reorder while streaming. The plan
must already be topologically sorted.

## Existing Pack Reuse

Existing pack reuse is a later optimization.

There are three levels:

1. reuse only canonical object content and generate new compression;
2. reuse existing whole-object compressed payloads when settings permit;
3. reuse existing delta entries and their base chains when all bases are valid
   for the output pack and the receiver.

Start with level 1. It is simpler, deterministic, and avoids exposing hidden or
invalid external bases.

Level 2 requires:

- matching object type and size;
- matching object id;
- known compressed payload boundaries;
- compatible compression and checksum handling;
- ability to stream existing bytes into the new pack checksum.

Level 3 requires:

- reconstructing or verifying the full delta chain;
- preserving base availability in the output;
- converting external bases to included bases unless thin-pack policy permits
  omission;
- avoiding delta chains that exceed the output policy limit;
- avoiding hidden-ref object leaks.

## Interaction With Pack Indexing

The pack index builder needs final object ids for every entry, including delta
entries.

For generated deltas, the planner already knows:

- target object id;
- final object type;
- uncompressed target size;
- pack entry offset after writing.

The index builder should not need to reconstruct generated deltas just to learn
the final object id. Tests can still reconstruct deltas to verify correctness.

For reused existing deltas, the reuse layer must provide the final object id from
existing indexes or reconstruction metadata.

## Interaction With Reachability

Delta selection must not change object reachability. It only changes pack
encoding.

For non-thin packs:

- every base needed by a delta must be present in the same pack or already
  present in the target repository after publication;
- upload-pack responses should include all delta bases unless thin-pack is
  explicitly selected.

For thin packs:

- every omitted base must be reachable from negotiated haves;
- hidden refs and protected objects must be excluded from the proof;
- shallow boundaries must be respected.

Reachability enumeration should provide candidate objects and known-haves. The
delta planner should not perform full graph negotiation by itself.

## Interaction With Partial Clone Filters

Future filter support such as `blob:none` or sparse path filters can interact
with deltas.

Initial rule:

- do not generate a delta whose base is filtered out of the response unless the
  base is a proven external thin-pack base.

For blob filters:

- if target blob is omitted, no delta is written;
- if target blob is included, its base must either be included or be a valid
  external base;
- avoid requiring the receiver to fetch a filtered-out base as part of resolving
  the pack.

## Interaction With Shallow Fetch

Shallow fetch can reduce the set of commits and objects the client is allowed to
receive or is known to have.

Thin-pack base proof must account for shallow state:

- a have behind a shallow boundary may not prove availability of all ancestors;
- external bases must be directly reachable from accepted client haves within
  the negotiated shallow rules;
- when unsure, include the base or fall back to whole-object output.

The first thin-pack implementation can refuse thin output for shallow requests.

## Interaction With S3 Backend

S3 repositories can serve generated packs by reading canonical object content
from pack storage.

Delta generation should avoid many random remote reads:

- bound candidate count;
- prefer bases already loaded for the request;
- use pack index locality when selecting candidates;
- use range-read batching when possible;
- spill large object contents to temporary storage rather than keeping many large
  byte arrays in memory.

The first S3 integration can keep no-delta output for upload-pack and enable
deltas only after local-file backend behavior is stable.

## Interaction With Maintenance Repack

Maintenance repack can use more expensive delta generation than interactive
upload-pack.

Repack policy may allow:

- larger candidate windows;
- deeper delta chains;
- deltas for trees, commits, and tags;
- reuse of existing deltas;
- pack locality optimization;
- bitmap or reachability-summary generation after writing.

Interactive upload-pack policy should remain cheaper:

- shallow chain depth;
- strict time budget;
- no expensive all-object candidate search;
- easy fallback to whole-object entries.

## Failure Handling

Delta generation failures must not corrupt repository state.

Failure cases:

- base object cannot be loaded;
- target object cannot be loaded;
- object type mismatch;
- candidate budget exceeded;
- memory budget exceeded;
- operation timeout;
- generated delta fails validation;
- encoded delta exceeds configured maximum;
- selected base is not valid for thin-pack omission;
- pack ordering cannot satisfy base-before-child constraints.

Default runtime behavior for optimization failures:

- log structured diagnostics;
- fall back to whole-object output when safe;
- fail hard only when the caller explicitly required delta output.

Validation failures in tests should fail hard.

## Limits

Add explicit limits with conservative defaults:

- maximum target object size for interactive delta generation;
- maximum base object size;
- maximum candidate bases per target;
- maximum total candidate bytes loaded per pack;
- maximum delta instruction count;
- maximum encoded delta size;
- maximum chain depth;
- maximum external bases per thin pack;
- maximum time per pack or per object;
- maximum memory reserved for rolling indexes.

Limits should be part of `GitDeltaGenerationSettings` and should appear in error
messages and metrics.

## Observability

Emit counters:

- objects considered for delta;
- objects written as whole entries;
- objects written as `REF_DELTA`;
- objects written as `OFS_DELTA`;
- generated delta bytes before compression;
- compressed delta bytes;
- whole-object compressed bytes estimate;
- fallback count by reason;
- thin-pack external base count;
- candidate bases loaded;
- candidate bases skipped by reason;
- generation time.

Debug logging should include:

- target object id;
- base object id;
- object type;
- selected delta kind;
- chain depth;
- fallback reason;
- omitted external base decision.

Do not log object contents.

## Security And Access Control

Delta generation must not expose objects that are hidden by access policy.

For upload-pack:

- candidate bases from hidden refs must not be selected unless the target object
  is already allowed and the base is also allowed by object visibility rules;
- thin external bases must be proven from visible negotiated haves;
- hidden refs must not be used as the only proof that the client has a base.

For protected refs:

- protected branch or tag policy affects which refs can be advertised or
  updated, not pack encoding directly;
- pack encoding must respect the visibility result from the access policy plan.

For receive-pack:

- incoming deltas can reference bases in the repository only if the update policy
  permits resolving those bases for the update.

## API Shape

Introduce a pack planning API:

```text
GitPackDeltaPlanner.plan(request, repositoryObjects, peerState, settings)
  -> GitPackDeltaPlan
```

`request` contains the target objects that must be sent or stored.

`repositoryObjects` provides object metadata, content readers, existing pack
locations, and optional path/history hints.

`peerState` contains negotiated capabilities and known haves. For local writes it
can be an empty non-thin state.

`settings` contains operation limits and policy flags.

The pack writer consumes `GitPackDeltaPlan`:

```text
GitPackWriter.write(plan, outputStream)
  -> GitPackBuildResult
```

The writer should not silently change a delta plan except for safe fallback
modes explicitly allowed by the plan.

## Phased Plan

Phase 1: Delta instruction encoder.

- Implement varint source/result size encoding.
- Implement insert instruction encoding with 127-byte splitting.
- Implement copy instruction encoding with Git opcode rules.
- Add tests for boundary sizes, zero-size objects, large copy sizes, and reserved
  opcode avoidance.

Phase 2: Delta instruction applier reuse.

- Reuse the incoming delta instruction parser/applier from reconstruction tests
  to validate generated programs.
- Add round-trip tests: base bytes plus generated delta equals target bytes.
- Keep this validation in tests first; add strict runtime validation flag later.

Phase 3: Simple byte-array delta generator.

- Add rolling-window candidate matching.
- Emit insert and copy instructions.
- Fall back to insert-only output for small or unmatched data.
- Add fixtures for text edits, prefix/suffix insertions, binary-like data,
  repeated blocks, and empty content.

Phase 4: Explicit delta pack entries.

- Extend pack builder inputs to accept caller-provided delta programs.
- Write `REF_DELTA` entries using base object ids.
- Build packs containing one whole base and one ref-delta child.
- Verify Orion parser, delta resolver, and Git/JGit can read the pack.

Phase 5: Delta decision and fallback policy.

- Add `GitDeltaDecision` and `GitPackDeltaPlan`.
- Compare generated delta cost against whole-object cost.
- Fall back to whole-object entries when deltas are larger or limits are hit.
- Add deterministic tie-breaking tests.

Phase 6: Blob base selection.

- Add candidate discovery for blobs using path/history hints where available.
- Add size-similarity filtering.
- Limit candidates per target.
- Add tests with multiple versions of the same file and unrelated files.

Phase 7: OFS_DELTA support.

- Track pack entry offsets during planning/writing.
- Topologically sort internal bases before delta children.
- Encode negative offsets.
- Verify generated packs with `OFS_DELTA` parse and reconstruct correctly.

Phase 8: Upload-pack non-thin delta output.

- Use delta planner for upload-pack when peer supports the required capabilities.
- Keep bases in the pack.
- Disable thin-pack by default.
- Add negotiation tests showing no `ofs-delta` use unless advertised.

Phase 9: Thin-pack planning.

- Add external-base metadata to `GitPackDeltaPlan`.
- Prove external bases from negotiated visible haves.
- Add a strict validator before streaming.
- Advertise `thin-pack` only after this phase passes.

Phase 10: Remote push integration.

- Keep no-delta push as default.
- Enable delta output only when remote capabilities allow it.
- Enable thin outgoing push only after remote haves are tracked with enough
  precision.

Phase 11: Pack reuse preparation.

- Expose existing pack entry metadata needed to reuse whole-object payloads.
- Keep delta reuse disabled until base-chain validation is available.
- Add compatibility tests for reused whole-object payloads.

Phase 12: Maintenance repack optimization.

- Add a separate, slower policy for repack jobs.
- Consider more candidates and deeper chains.
- Reuse existing deltas where safe.
- Verify repack preserves every reachable object id.

## Verification

Instruction encoding:

- source size varint round-trips;
- result size varint round-trips;
- insert instruction splits at 127 bytes;
- copy instruction encodes offset and size bytes correctly;
- copy size 0x10000 uses Git's default-size encoding;
- reserved opcode is never emitted.

Delta generation:

- insert-only delta reconstructs target bytes;
- copy-only delta reconstructs target bytes;
- mixed insert/copy delta reconstructs target bytes;
- repeated blocks choose deterministic matches;
- generator falls back when output is larger than whole-object policy permits;
- generator respects instruction-count and byte-size limits.

Pack writing:

- `REF_DELTA` pack with internal base parses and reconstructs;
- `OFS_DELTA` pack with internal base parses and reconstructs;
- base-before-child ordering is enforced;
- pack checksum is valid;
- pack index receives final target object ids.

Thin-pack:

- external base is allowed only when peer advertised `thin-pack`;
- external base must be reachable from visible negotiated haves;
- missing proof forces base inclusion or whole-object fallback;
- hidden-ref-only base proof is rejected;
- shallow request disables thin-pack or proves bases within shallow rules.

Integration:

- upload-pack does not advertise thin-pack before implementation;
- upload-pack sends non-thin delta packs when allowed;
- remote single-file push can continue using no-delta packs;
- save-files write path can keep delta generation disabled by default;
- maintenance repack can enable a slower policy without changing upload-pack
  defaults.

Interoperability:

- `git index-pack` accepts generated non-thin packs;
- `git unpack-objects` accepts generated packs where useful in tests;
- JGit can parse and reconstruct generated packs used as compatibility fixtures;
- Orion parser and delta resolver agree on final object ids.

## Test Fixtures

Add small deterministic fixtures:

- empty base to non-empty target;
- non-empty base to empty target;
- line insertion in middle of text;
- line deletion from text;
- prefix insertion;
- suffix insertion;
- repeated phrase where multiple base offsets match;
- binary-like bytes with zero bytes and high-bit bytes;
- large copy that crosses 0x10000 size encoding;
- two blobs with same path history;
- several unrelated blobs where no delta should be selected.

Add pack-level fixtures:

- one base blob plus one `REF_DELTA` child;
- one base blob plus one `OFS_DELTA` child;
- chain depth one;
- chain depth limit exceeded;
- thin-pack candidate with valid external base;
- thin-pack candidate without valid external base.

## Rollout

Default runtime settings:

- delta generation disabled for native writes until no-delta pack builder is
  stable;
- `REF_DELTA` enabled before `OFS_DELTA`;
- thin-pack disabled until upload-pack validation is complete;
- existing pack/delta reuse disabled until explicit reuse phases are complete.

Enablement order:

1. unit-tested delta instruction encoder;
2. generated deltas in isolated pack builder tests;
3. non-thin generated deltas in local backend upload-pack tests;
4. optional non-thin generated deltas in S3 upload-pack tests;
5. thin-pack advertisement and serving;
6. repack optimization policy.

Every phase should preserve a no-delta fallback switch.

## Open Questions

Should the first rolling index use fixed 16-byte windows, or should the window
size depend on object size?

Should phase one generate deltas only for blobs, or also for tree objects after
the encoder is available?

Should `REF_DELTA` remain the default for small packs even after `OFS_DELTA`
works, or should Orion prefer `OFS_DELTA` whenever the peer advertises it?

How exact should compressed-size estimation be before writing the pack?

Should interactive upload-pack validate generated deltas at runtime in strict
mode only, or always validate small generated deltas before streaming?

What path/history hints should the native object model expose so base selection
does not need to understand higher-level file-change semantics?

Should pack reuse be implemented before or after maintenance repack?

How should thin-pack external base proofs be represented for audit events?

## Acceptance Criteria

Orion can generate a valid Git delta program from one object content buffer to
another and prove that applying the program reconstructs the target bytes.

The native pack writer can write valid `REF_DELTA` and `OFS_DELTA` entries
without JGit.

Delta-enabled pack output is deterministic for fixed inputs and settings.

Delta generation falls back to whole-object entries when the optimization is not
safe or not useful.

Upload-pack does not advertise or use thin-pack until it can prove every omitted
base from negotiated visible haves.

All delta-generated pack entries still produce correct final object ids for pack
indexing, object lookup, and repository publication.

No runtime path requires delta generation for correctness; no-delta output
remains a supported fallback.

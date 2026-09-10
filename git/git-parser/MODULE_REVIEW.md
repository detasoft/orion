# Module Review: `git/git-parser`

Date: 2026-09-08
Reviewed revision: `f1075fe8ca16a43134dbb21cddf208f6bb995487`
Status: reviewed with direct consumers; five structural findings remain

## Scope and coverage

This review applies `minimal-delta`, `architecture-simplifier`, and `architecture-review` to pkt-line
framing, transport bootstrap, legacy upload-pack negotiation, receive-pack, protocol v2 commands, and
response serialization. It traces the principal production flows into `git-client`, `net/git-transport`,
Smart HTTP in `net/http-core`, native storage, and the proxy-aware repository provider.

Representative tests were inspected for exact response bytes, fragmented input, negotiation rounds,
shallow history, malformed receive packs, atomic and non-atomic ref updates, producer closure, access
checks, and proxy refresh behavior. This is not an exhaustive review of the adjacent modules, external
binary consumers, or Git protocol compliance.

The analysis was static. Maven verification was not run, following
[repository review rules](../../docs/reviews/RULES.md). The review found no per-I/O timeout thread
allocation in the inspected production paths; transport timeout enforcement was not audited exhaustively.
Production code, tests, build files, and task metadata were not changed by this review.

All five primary findings in the
[2026-09-03 baseline](../../docs/reviews/2026-09-03-git-parser-architecture-simplification.md) remain
present. This document records current evidence and qualifications, especially around repository refresh
semantics. It does not mark the existing
[simplification tasks](../../docs/plans/tasks/11_git/01_wire-architecture-simplification/TASK.md)
complete or claim that their post-implementation review has happened.

## Current conceptual model

TCP, SSH, and Smart HTTP entrypoints adapt their streams to `BufferedByteInput` and `BufferedByteOutput`.
`GitWireBootstrap` parses transport-specific request metadata into `InitialRequestData` and constructs
`GitBlockingWireTransport`. The entrypoint then constructs `GitBlockingWireSession` with a repository
service, access hook, configuration, and packfile-URI source factory.

The main flows are:

1. Legacy upload advertises refs, reads wants and shallow options, processes have/ACK rounds, and sends
   a raw or sideband pack. Stateful connections retain negotiation state across flush-delimited rounds;
   a stateless HTTP round can end without sending a pack.
2. Protocol v2 dispatches `ls-refs` and `fetch`. A fetch without `done` produces acknowledgments; a
   completed fetch produces optional metadata sections followed by the pack. One connection can carry
   successive v2 commands.
3. Receive-pack parses commands, optionally ingests a pack into quarantine, validates object closure
   and per-ref access, publishes through the repository provider, and sends negotiated status output.

Ownership is split as follows:

| Resource or state | Current owner |
| --- | --- |
| Connections, execution threads, transport shutdown | TCP/SSH/HTTP adapters |
| Packet reads and writes | `GitBlockingWireTransport` over buffered byte I/O |
| Wants, haves, capabilities, parser accumulators | One `GitBlockingWireSession` execution |
| Repository resolution and operation-specific authorization | `DefaultGitNativeRepositoryService` |
| Pack producer closure | Response wrappers plus defensive session cleanup |
| Pack ingestion and quarantine handoff | Session coordinating native storage types |
| Objects, refs, publication, proxy refresh and upstream writes | Repository and provider implementations |

The parser has no durable recovery protocol or scheduler of its own. Its state is ordinary control flow
and operation-local collections. The public Java surface nevertheless combines wire values, native
storage types, repository coordination, and response lifecycle objects.

## Highest-value findings

### 1. The parser/storage boundary does not isolate the shared wire code

**Finding.** Wire clients inherit the native server storage implementation, while the parser exposes
storage ownership and publication concepts in its API.

**Evidence.** The [module POM](pom.xml) depends directly on `git-native-storage`.
[GitNativeRepositoryService](src/main/java/pro/deta/orion/git/parser/wire/GitNativeRepositoryService.java)
exposes `NativeFetchRequest`, `NativeFetchResponse`, `NativePackProducer`, and `PackIngestionSession`.
[GitBlockingWireSession](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java)
constructs `PackIngestionOutput` and transfers a `LooseObjectStore` quarantine in `LegacyReceivePack`.
The pack response methods in
[GitBlockingWireTransport](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java)
also depend on native storage types.

By contrast,
[GitBlockingClientWire](../git-client/src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java)
uses packet and byte-stream operations. Its [POM](../git-client/pom.xml) inherits storage through the
parser dependency. The concrete repository service already lives in `net/git-transport`.

**Why it likely exists.** The
[parser/storage task](../../docs/plans/tasks/11_git/01_wire-architecture-simplification/03_parser-storage-boundary.md) records that the blocking migration deliberately
placed the server session, including receive-pack ingestion, in the parser during the execution-model
replacement. That placement now couples shared protocol machinery to one server implementation.

**Smallest simplification.** Move server session and repository/pack coordination to the existing
`net/git-transport` module. Keep framing, bootstrap parsing, and storage-neutral wire serialization in
`git-parser`. Moving only the session is insufficient: native producer and metadata types must also
leave the parser's response API. Reuse buffered output to connect server-owned pack production to wire
framing; do not create another module or a parallel hierarchy of storage-neutral pack adapters.

**Contract and risk.** Protocol bytes and runtime behavior should remain unchanged. Internal Java APIs,
type placement, and Maven dependencies change, so all in-repository consumers must move in the same
change and the old path must be removed. Coordinate `GitObjectId` placement without introducing a shared
module solely for that value.

**Consequences.** The client no longer depends on server storage, and server resource ownership can be
understood in one module. The parser ceases to be the entrypoint for Orion-specific repository execution.

**Confidence.** High for the dependency problem and target ownership. Exact type placement requires a
focused implementation pass over the affected consumers.

### 2. Blocking output retains resumable operation objects and duplicated closure logic

**Finding.** Output models partial progress even though its production callers perform one synchronous
write operation. This adds state and fragments producer ownership without a verified resumable caller.

**Evidence.**
[PacketListSerialization](src/main/java/pro/deta/orion/git/parser/wire/serialization/PacketListSerialization.java)
stores `packetOffset`, but never assigns it a nonzero value.
[PktLineSerialization](src/main/java/pro/deta/orion/git/parser/wire/serialization/PktLineSerialization.java)
sets its offset to the complete packet length before writing the packet.
[OutputSerialization](src/main/java/pro/deta/orion/git/parser/wire/serialization/OutputSerialization.java)
and `AsciiPacketSequenceSerialization` add operation objects and intermediate packet collections around
ordinary blocking writes.

`LegacySideBandResponse`, `LegacyPackResponse`, and `ProtocolV2PackfileResponse` each complete and close
their producer in one `advance()` call. Their only production caller is `GitBlockingWireSession`.
Each repeats a `closed` flag, failure cleanup, and close handling, while the session also closes the
response in `finally`.

There is a concrete ownership gap in `serveFetch`: the producer already exists when
`beginProtocolV2Packfile` constructs and validates the response. If construction throws, `response`
remains null and the session's `finally` does not close the producer. This follows from static control
flow; the review did not reproduce it with a test.

**Why it likely exists.** These shapes preserve yield/resume concepts from the earlier continuation
model, despite the explicit move to blocking output.

**Smallest simplification.** Delete the four external serialization types and replace their callers
with direct writes. Replace the three response wrappers with one-shot `send...` methods. Give each send
method responsibility for producer closure from entry through validation, writing, flush, and failure.
Keep protocol-specific encoding local rather than replacing the wrappers with a new generic hierarchy.

**Contract and risk.** Remove the internal `begin/advance/close` API. Preserve bounded pack streaming,
blocking backpressure, exact framing, flush boundaries, and useful exception causes. Preserve validation
before response commitment where the current code validates an entire structured response before writing.
An I/O failure must not become an implicit retry of partially written bytes.

**Consequences.** Four external types and three nested response types can disappear, along with cursor
state and repeated closure code. Streaming and resource cleanup remain required behavior.

**Confidence.** High. Existing byte-level and producer-closure tests provide concrete migration checks.

### 3. A repository operation repeatedly reconstructs context, including upstream refresh

**Finding.** The session repeatedly passes the same repository identity and access hook through a
stateless service API. Each call can reopen the repository and repeat work with observable side effects.

**Evidence.** `readLegacyUploadNegotiation` calls `commonHaves` once per received `have`.
[DefaultGitNativeRepositoryService](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java)
resolves the repository again in `commonHaves`, `legacyUploadReady`, and fetch operations. Receive-pack
resolves it for advertisement, ingestion, and completion; publication goes through the provider.

This is more than a repeated map lookup.
[ProxyAwareNativeGitRepositoryProvider](../git-native-proxy/src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java)
routes `openForRead` and `openForWrite` through `policyBound`, which calls `proxy.refresh()` and returns
a policy-bound handle. Its tests distinguish refreshing before each logical read from using one handle
for multiple repository reads.

**Why it likely exists.** A singleton service with independently callable methods reconstructs all
required context at each entry. That model does not express that several entries form one Git operation.

**Smallest simplification.** Bind the resolved handle, repository identity, and access hook to the
existing server operation owner. Keep per-want fetch checks and per-ref update checks where their inputs
become available. Do not introduce a cache, registry, or context hierarchy. The exact logical-operation
boundary must be settled before reducing provider calls.

**Contract and risk.** Reusing a handle changes upstream refresh frequency and potentially repeated
coarse authorization checks. A single TCP/SSH connection can carry multiple v2 commands, so connection
lifetime cannot automatically be equated with one logical read. HTTP discovery and POST remain separate
operations. A retained handle is not an immutable ref snapshot. Publication must retain proxy routing
and upstream compare-and-set behavior rather than bypassing the provider to mutate local storage.

**Consequences.** Fewer repeated lookups and upstream refreshes, fewer repeated parameters, and clearer
operation ownership. The trade-off is reduced observation of upstream changes between formerly separate
service calls; its acceptable boundary is a product contract, not a mechanical cleanup choice.

**Confidence.** High on repeated work and refresh side effects; medium on the safe reuse boundary.

### 4. Capability advertisement and request acceptance have separate sources of truth

**Finding.** Availability is represented by configuration booleans, capability values, advertisement
assembly, and independent request checks. These representations already disagree.

**Evidence.**
[GitWireConfiguration](src/main/java/pro/deta/orion/git/parser/wire/GitWireConfiguration.java) contains
22 booleans across three sections. The repository service builds legacy advertisements; the transport
builds v2 command and feature strings. TCP, SSH, and HTTP entrypoints independently use `allSupported()`.

The session checks whether both v2 commands are disabled, but `readV2CommandPayload` and
`serveV2Command` do not enforce the individual flag for the selected command. `FetchAccumulator`
accepts `wait-for-done` without checking `configuration.protocolV2().waitForDone()`. Other feature
branches do check their flags. Current production entrypoints enable all features, so this is a latent
configuration-contract mismatch rather than evidence that default production requests are failing.

**Why it likely exists.** Legacy and v2 advertisements have different wire shapes, and feature additions
were expressed separately in configuration, advertisement builders, and parser branches.

**Smallest simplification.** Derive advertisement and negotiation decisions from one effective set of
implemented, permitted capabilities. The existing task already requires an immutable global veto.
Implement it as a replacement for advertisement-only booleans, with one composition-time owner and
canonical capability names. Preserve genuine implementation settings. The veto must not enable an
unsupported capability or create a second configuration model.

**Contract and risk.** Default advertisements must remain byte-for-byte equivalent. A hidden command or
feature must no longer be selectable by a client; that corrects acceptance behavior for restricted
configurations. Parent/child features and valued capability names need explicit coverage.

**Consequences.** One availability decision applies across protocols and transports, and parser checks
cannot silently diverge from advertised support.

**Confidence.** High on duplication and the acceptance mismatch. The global-veto requirement is recorded
in the existing task tree rather than inferred from hypothetical extensibility.

### 5. `GitObjectId` does not own its identity rules

**Finding.** The value type preserves arbitrary spelling and delegates format and identity rules to
consumers, which repeat validation and normalize inconsistently.

**Evidence.**
[GitObjectId](../git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/GitObjectId.java)
checks only for null. The session, transport, and `LegacyReceiveCommand` repeat 40-hex validation.
Some parsing paths lowercase accepted IDs; others pass the original spelling into `GitObjectId.of`.
Record equality and hashing therefore distinguish uppercase and lowercase spellings.
[LooseObjectStore](../git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/object/LooseObjectStore.java)
uses `id.value()` for cache and path lookup, so spelling can also affect object retrieval.

**Why it likely exists.** The type currently acts as a lightweight string wrapper. No inspected consumer
requires case-sensitive identity for a typed object ID.

**Smallest simplification.** Validate the supported SHA-1 form and canonicalize to lowercase at creation
of the existing type. Define zero identity there. Delete validation and normalization of values that
are already typed. Keep raw wire and persistence conversion explicit; do not add a hash-algorithm
abstraction or convert unrelated client-facing string APIs.

**Contract and risk.** Equality, hashing, canonical output spelling, and the point where malformed IDs
are rejected change. Inspect existing typed construction and persisted inputs before removing boundary
checks; do not treat invalid historical values as an implicit new supported format.

**Consequences.** One value invariant removes repeated validation and case-sensitive lookup mistakes
without introducing a new domain concept.

**Confidence.** High.

## Things to try deleting

The main removal set is the four external serializers, three response wrappers, and the parser's native
storage dependency described above. After those focused changes, reassess these smaller candidates:

| Candidate | Condition for removal |
| --- | --- |
| `LegacyUploadNegotiation` | Move its conversion onto the existing request with explicit `haves`; the session is its only production consumer. |
| Duplicate `ReceivePackStatus` and `ReceiveCommandStatus` | Use one command-result value after fixing ownership; the session currently copies identical fields. |
| `RawPackOutput` | Pass the existing sink directly after verifying empty-write behavior and buffer ownership. |
| Cursors in `ReceivePackStatusSerialization` | Encode packets in an ordinary loop while preserving nested sideband framing and validation. |

The internal status serializer also reconstructs UTF-8 payloads repeatedly from byte-at-a-time helpers.
Its removal can eliminate that work, but this review makes no benchmark claim. Keep these smaller
changes separate from the deliberately narrow existing blocking-output task.

## Proposed conceptual model

- `git-parser` owns packet framing, bootstrap parsing, and storage-neutral protocol values and writes.
- The existing server module owns the Git operation, request negotiation, repository handle, and
  action-specific authorization.
- Provider/storage owns object and ref state, quarantine publication, and proxy policy.
- Each pack send has one producer owner and one blocking execution path.
- One effective capability decision drives advertisement and request acceptance.
- `GitObjectId` owns typed identity; consumers do not reconstruct that invariant.

The required delta is fewer owners and representations while preserving protocol bytes, streaming,
authorization, ref publication, and transport lifecycle. No new module, scheduler, persistent state,
registry, or dependency is justified. Any operation context should remain local to its existing owner;
the capability policy replaces existing switches and is justified by the recorded veto requirement.

## Incremental migration path

1. Canonicalize `GitObjectId` and update typed consumers. Validate accepted case variants, invalid input,
   equality and hashing, zero identity, and object retrieval through the existing storage paths.
2. Complete the narrow blocking-output migration. Preserve byte-level tests in `GitBlockingWireTransportTest`,
   `ProtocolV2PackfileResponseTest`, and `ProtocolV2ShallowInfoResponseTest`. Add closure coverage for
   response preparation, write, flush, and runtime failures while retaining chunked pack production.
3. Move server/storage coordination out of parser and update all consumers together. Verify the Maven
   dependency boundary and TCP, SSH, and Smart HTTP composition; preserve packet tests in parser and
   server behavior tests beside their owner.
4. Define the logical-operation boundary for provider refresh and authorization, then bind one handle
   within that boundary. Use counting-provider and proxy tests to verify refresh frequency, publication
   routing, missing repositories, denied access, and successive commands on one connection.
5. Replace scattered advertisement switches with the required global policy. Check default bytes,
   legacy and valued capabilities, v2 commands, child features, and rejection of hidden capabilities.
6. Re-audit the smaller model and handle secondary deletions independently. Update every real consumer
   and delete each replaced production API in its implementation change. Remove legacy-only negative
   checks in a separate commit as required by repository policy.

These are separately reviewable changes, not a single cross-module rewrite. Implementation owns focused
verification and the repository's required commit-time tests; no test result is claimed by this document.

## Do not change

- Preserve bounded streaming and blocking backpressure; do not buffer a complete pack to simplify output.
- Preserve ByteBuf ownership and release obligations, producer closure, and ingestion cleanup.
- Preserve quarantine until object-closure validation and publication, stale-ref compare-and-set behavior,
  and distinct atomic versus non-atomic receive semantics.
- Preserve repository access checks and per-want/per-ref authorization at the appropriate boundaries.
- Preserve proxy-aware publication; a local repository handle must not bypass upstream policy.
- Preserve stateful negotiation rounds, separate HTTP requests, successive v2 commands, and section ordering.
- Preserve distinct direct and unborn ref values and legitimate transport-specific packfile-URI sources.
- Preserve useful wire-error classification for diagnostics when simplifying output objects.
- Keep timeout and connection ownership in byte I/O and transport adapters; do not add parser timeout threads.

## Open questions

1. What is one logical operation for upstream refresh: a complete connection, a v2 command, or a negotiation
   round? The provider's existing per-logical-read behavior makes this an observable contract.
2. Must coarse access checks observe policy changes within a long-lived connection, or are they bound to
   an individual command? Per-want and per-ref checks remain necessary regardless of that answer.
3. What freshness is required between successive v2 commands? Reusing a repository object neither refreshes
   upstream automatically nor freezes concurrent local ref changes.

These questions qualify the command-context recommendation. They do not prevent the independently
supported serializer and cursor removals.

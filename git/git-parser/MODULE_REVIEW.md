# Module Review: `git/git-parser`

## 2. Inactive output APIs retain obsolete serializers and tests

**Problem and evidence.** [GitBlockingWireTransport](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java)
retains `sendV2UploadPackAdvertisement`, `sendLsRefs`, `sendError`, and `writeSideBand*` methods whose only
callers are tests. `writeDelimiter` and `writeRaw` have no callers. Active advertisement is owned by
[GitBlockingWireSession](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java); refs use
[RefsCommand](src/main/java/pro/deta/orion/git/parser/v2/command/RefsCommand.java) and `GitProtocolContext.Writer`;
pack framing uses `GitPktLineOutput`.

The five classes in [wire/serialization](src/main/java/pro/deta/orion/git/parser/wire/serialization)
serve these inactive APIs. `PacketListSerialization` additionally serves the live legacy advertisement.
Its `packetIndex`, and `PktLineSerialization.written`, model progress although each object receives one
synchronous invocation. No resumable caller was found.
[GitLsRefsResponse](src/main/java/pro/deta/orion/git/parser/wire/advertisement/GitLsRefsResponse.java) remains a production
DTO only for the inactive serializer; the other uses are decoded values in transport test support.

**Required contract.** Preserve the live legacy advertisement, complete validation before its first
write, packet boundaries, flushes, bounded output, and borrowed-buffer ownership. No current requirement
supports the parallel response encoders or resumable operation objects.

**Minimal repair and tests.** Remove the inactive methods and all five serialization files after replacing
the live advertisement wrapper with a packet loop and flush. Move the test response representation into
existing test support, or assert decoded values directly, before removing `GitLsRefsResponse`. Preserve
[GitWireRefsTest](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/GitWireRefsTest.java).

In [GitBlockingWireTransportTest](src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransportTest.java):

- Remove `sendsProtocolV2UploadPackAdvertisement`, `rejectsInvalidLsRefsObjectId`, and
  `rejectsBlankGitErrorMessage` with their inactive APIs. Typed identity validation remains in `GitIdTest`.
- Preserve the large-response scenario from `writesLargeResponseSynchronouslyToBufferedByteOutput` through
  active `RefsCommand` or session behavior before removing the old test.
- Remove tests of the obsolete sideband helpers only while preserving channel, split-limit, framing and
  buffer-ownership checks through `GitPktLineWriteTest`, `GitPktLineOutputTest`, and active pack tests.
- Keep legacy advertisement, packet/raw switching, malformed input, and other live transport tests.

**Alternatives and consequences.** Keeping the old encoders maintains two production-shaped protocol
paths. Deletion changes internal Java APIs; it need not change active wire behavior. The transport itself
is still required by `GitBlockingClientWire` and must remain.

**Confidence and priority.** High for call-site evidence. Medium importance and medium repair ease:
serializer removal is local, but useful behavioral tests and the live advertisement must be migrated.

## 9. An unused error record preserves a retired taxonomy

**Problem and evidence.** [GitWireError](src/main/java/pro/deta/orion/git/parser/wire/error/GitWireError.java)
is never instantiated and its `Phase` enum has no consumer. Only three `Kind` values remain live:
`RESERVED_LENGTH`, `INVALID_HEX_HEADER`, and `LENGTH_EXCEEDS_LIMIT` in
[GitPktLine](src/main/java/pro/deta/orion/git/parser/v2/pkt/GitPktLine.java). Each is wrapped in
`GitGeneralException` and immediately in `GitPktLineFormatException`; no caller inspects the intermediate
exception or the error record.

**Required contract.** Preserve the format-exception type used by `GitBlockingClientWire`, the distinction
between malformed cases in diagnostics, and truncated/clean-EOF handling. No current consumer requires
the retired phases, unused kinds, or intermediate wrapper.

**Minimal repair and tests.** Express the three header errors directly through the existing
`GitPktLineFormatException` path and remove `GitWireError` and `GitGeneralException`. Preserve malformed,
reserved, oversized and truncated pkt-line tests; no behavioral test class is obsolete here.

**Alternatives and consequences.** Keeping a smaller enum is possible if a real typed consumer appears;
none exists now. Exception-cause shape changes, so preserve useful message detail and the externally
caught exception type.

**Confidence and priority.** High for current consumers; low importance and medium repair ease because
error classification and diagnostics must remain stable.

## 10. HTTP v2 responses contain an internal marker rejected by Git

**Problem.** Every HTTP v2 command appends `0002` to the HTTP body. C Git rejects it with
`remote-curl: unexpected response end packet`, affecting ls-refs and fetch.
**Sources.** [serveV2](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java#L149),
[HTTP caller](../../net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java#L242),
[parser assertion](src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireSessionTest.java#L215),
[transport assertion](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java#L196).
Both tests explicitly expect the unwanted trailing marker.
**Documented behavior and contract.** Upstream's
[HTTP response parser](https://github.com/git/git/blob/master/remote-curl.c#L739-L757) rejects incoming 0002.
The [HTTP helper](https://github.com/git/git/blob/master/remote-curl.c#L1024-L1025) adds it to its internal pipe
after the HTTP response ends. HTTP-body framing and internal stateless framing are distinct boundaries.
**Minimal repair.** Remove the HTTP writeResponseEnd call, retain command flush/response completion,
update the byte assertions and add actual C Git HTTP v2 interoperability coverage.
**Alternatives and consequences.** Do not remove 0002 support from generic framing: other internal/stateless
consumers may legitimately use it. This is a local HTTP-boundary correction.
**Confidence.** High from emitted bytes and the reference rejection branch; no live HTTP reproduction run.
**Priority signals.** Importance high: ordinary HTTP v2 requests fail. Repair ease high: one unwanted write
and known assertions, without new state or abstractions.

## 11. Legacy upload advertisements omit peeled annotated tags

**Problem.** A ref pointing to an annotated tag is advertised only with its tag-object ID, never with
the immediately following peeled-target record `refs/tags/name^{}`.
**Sources.** [legacyAdvertisement](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java#L206)
constructs direct refs exclusively. [GitAdvertisedRef](src/main/java/pro/deta/orion/git/parser/wire/advertisement/GitAdvertisedRef.java)
and the existing encoder already support peeled IDs. [RefsCommand](src/main/java/pro/deta/orion/git/parser/v2/command/RefsCommand.java#L88)
implements tag traversal; its tests cover nested tags, while legacy session advertisement tests cover direct refs.
**Documented behavior and contract.** Git's
[Reference Discovery specification](https://github.com/git/git/blob/master/Documentation/gitprotocol-pack.adoc#reference-discovery)
requires annotated tags to be peeled and the peeled record to follow the original ref.
**Minimal repair.** Supply peeled values using existing traversal and representation. Preserve HEAD-first
ordering, capability placement and lightweight tags; test active legacy discovery with nested tags.
Also check [native want validation](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/NativeGitRepositoryContext.java)
for acceptance of newly advertised peeled targets.
**Alternatives and consequences.** A second tag parser adds duplication; later client retrieval does not
satisfy the discovery contract. Receive-pack behavior must remain appropriate to that service.
**Confidence.** High from unconditional direct-ref construction; no live legacy tag experiment performed.
**Priority signals.** Importance medium for tag discovery; repair ease medium because advertisement and
allowed-want consumers must agree.

## 12. Incoming pack version 3 is rejected

**Problem.** A correctly checksummed SHA-1 pack with header version 3 fails before object ingestion.
Completion contains a second version-2-only check.
**Sources.** [PackIngestor](src/main/java/pro/deta/orion/git/parser/v2/pack/PackIngestor.java#L46),
[resolver header check](src/main/java/pro/deta/orion/git/parser/v2/pack/GitPackObjectResolver.java#L187),
[PushCommand](src/main/java/pro/deta/orion/git/parser/v2/command/PushCommand.java),
[header tests](src/test/java/pro/deta/orion/git/parser/v2/pack/PackIngestorTest.java#L174).
The existing modified-version fixture does not recompute its trailer and is not a valid version-3 example.
**Documented behavior and contract.** The
[pack format](https://github.com/git/git/blob/master/Documentation/gitformat-pack.adoc) and
[Git version predicate](https://github.com/git/git/blob/master/pack.h) accept versions 2 and 3;
Git normally generates version 2.
**Minimal repair.** Accept both versions in ingestion and completion, retaining version-2 output.
Test a nonempty version-3 pack with valid trailer through resolution/publication and retain unsupported-version rejection.
**Alternatives and consequences.** A separate decoder is unnecessary. Changing only the ingestor leaves
the later rejection. Documenting a restricted subset would not resolve this interoperability limitation.
**Confidence.** High on explicit predicates and upstream support; occurrence frequency not measured.
**Priority signals.** Importance medium for valid but uncommon packs; repair ease high, two local checks.

## 13. Advertised server-option rejects legal values containing spaces

**Problem.** A v2 header `server-option=foo bar` fails before dispatch despite server-option advertisement.
**Sources.** [advertisement and header parser](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java#L131),
[generic value validation](src/main/java/pro/deta/orion/git/parser/v2/capability/GitCapabilityValue.java),
[existing server-option test](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java#L298).
The test uses only `server-option=trace`; generic validation rejects whitespace before recognizing the option.
**Documented behavior and contract.** Git's
[server-option specification](https://github.com/git/git/blob/master/Documentation/gitprotocol-v2.adoc#server-option)
excludes NUL/LF in option payloads; a space is not a capability separator inside this pkt-line.
**Minimal repair.** Parse the option under its own header-payload rule before generic token validation.
Cover spaced/repeated options and invalid bytes through command dispatch; preserve advertised-feature checks.
**Alternatives and consequences.** Globally allowing spaces weakens unrelated legacy capability rules.
No new option service or public abstraction is necessary; unknown-option semantics can remain unchanged.
**Confidence.** High for spaced values, without assuming a meaning for any server-specific option.
**Priority signals.** Importance medium for a legal advertised-feature request; repair ease high and local.

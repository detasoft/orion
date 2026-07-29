# Task: Streaming Git Protocol V2 Response Parsers

## Objective

Implement streaming parsers for Git protocol v2 `ls-refs` and `fetch`
responses on top of the semantic-phase support already added to
`GitMinimalWireMachine`.

The parsers must:

- accept arbitrarily fragmented `ByteBuf` chunks;
- keep durable parsing state inside `GitMinimalWireMachine`;
- pass completed intermediate values between phases through the machine-owned
  `GitWireValueStack`;
- expose typed success or failure through `GitWireOutcome`;
- forward packfile data without retaining it in response models.

## Existing Foundation

The implementation starts from these existing pieces:

- `GitMinimalWireMachine` owns pkt-line header and payload fragmentation;
- semantic phases consume completed control and payload events;
- `GitWireValueStack` provides checked phase-to-phase value handoff;
- malformed semantic input becomes `GitWireFailure` and a terminal failed
  machine state;
- `GitSideBandDecoder` already demultiplexes band-one pack data, progress, and
  fatal messages;
- the v1 advertisement parser demonstrates typed factories, semantic phases,
  stack handoff, terminal outcomes, and close-time validation.

Read before implementation:

- `docs/plans/2026-07-27-git-wire-response-parsers-design.md`;
- `docs/plans/2026-07-27-git-wire-response-parsers.md`;
- [Git protocol v2 specification](https://git-scm.com/docs/gitprotocol-v2).

## Part 1: `ls-refs` Response

Add a typed machine factory:

```java
GitMinimalWireMachine.forV2LsRefsResponse(ByteBufAllocator allocator)
```

Parse each ref row:

```text
<object-id-or-unborn> <ref-name> [<attribute>...]
```

Known attributes:

- `symref-target:<ref-name>`;
- `peeled:<object-id>`;
- `unborn` through the first row field defined by protocol v2.

Unknown attributes must be preserved as raw name/value tokens so later Git
extensions do not require changing the parser.

The final model should contain an ordered immutable list of refs. Each ref
contains:

- object id or explicit unborn state;
- ref name;
- optional symref target;
- optional peeled object id;
- preserved unknown attributes.

Validation:

- reject malformed or missing object ids and ref names;
- reject duplicate refs;
- reject duplicate known attributes;
- reject missing values for `symref-target:` and `peeled:`;
- reject peeled data on an unborn ref;
- reject unexpected delimiters and section headers;
- report packet index, byte offset, and `LS_REFS_RESPONSE` phase.

The structured ref list closes with `FLUSH`. For the Orion stateless response
path described in `TASKS.md`, the outer response completes with
`RESPONSE_END`. Do not silently complete before the expected response-end
packet. If a future stateful transport needs flush-only completion, add an
explicit terminal policy rather than making the sequence ambiguous.

## Part 2: Structured `fetch` Response

Add a typed machine factory accepting the pack-data target and progress
consumer:

```java
GitMinimalWireMachine.forV2FetchResponse(
        ByteBufAllocator allocator,
        RawTargetFactory rawTargetFactory,
        Consumer<String> progressConsumer)
```

Recognize these sections in protocol order:

1. `acknowledgments`;
2. `shallow-info`;
3. `wanted-refs`;
4. `packfile`.

Sections are separated with `DELIMITER`. A section may be absent where the Git
protocol permits it, but a section must not repeat or move backwards.

### Acknowledgments

Parse:

```text
NAK
ACK <object-id>
ready
```

Rules:

- `NAK` and `ACK` rows are mutually exclusive;
- object ids must be valid;
- `ready` may occur at most once;
- an acknowledgments-only negotiation response may finish without packfile.

Push a completed `GitFetchAcknowledgments` value before moving to the next
section.

### Shallow Info

Parse:

```text
shallow <object-id>
unshallow <object-id>
```

Reject duplicate or contradictory rows for the same object id. Push an
immutable `GitFetchShallowInfo` value.

### Wanted Refs

Parse:

```text
<object-id> <ref-name>
```

Preserve order and reject duplicate ref names. Push an immutable list of
`GitFetchWantedRef` values.

### Final Structured Result

The completed `GitFetchResponse` should contain:

- acknowledgments, if received;
- shallow and unshallow object ids;
- wanted refs;
- received section order;
- whether a packfile section was entered.

It must not contain pack bytes, retained `ByteBuf` instances, or complete
side-band packets.

## Part 3: Packfile Side-Band Transition

After the `packfile` section header, transition the wire machine into a
side-band phase.

Required behavior:

- band 1 is forwarded as retained slices to a lazily created `RawSink.Target`;
- band 2 is delivered to the progress consumer;
- band 3 becomes the existing typed fatal side-band error;
- fragmented side-band headers, band ids, and payloads remain supported;
- the raw target is not created for an empty pack stream or progress-only
  traffic;
- pack bytes never pass through `GitWireValueStack`.

When `GitSideBandDecoder` consumes its terminal flush, it must leave any
following outer response bytes unread. `GitMinimalWireMachine` then resumes its
control phase and consumes `RESPONSE_END`, which produces the final
`GitFetchResponse`.

Closing the machine must close the side-band decoder and any created raw target
exactly once.

## Stack and Phase Expectations

The semantic flow should visibly use the operand stack:

```text
AcknowledgmentsPhase
  -> push(GitFetchAcknowledgments)
  -> ShallowInfoPhase pops/uses prior values
  -> push(GitFetchShallowInfo)
  -> WantedRefsPhase
  -> push(wanted refs)
  -> PackfilePhase
  -> push(GitFetchResponse)
  -> CompletedPhase
```

Phases may omit absent optional values, but they must consume intermediate
values before leaving the final response on top of the stack. A stack type
mismatch is an internal programming error, not malformed remote traffic.

Malformed remote traffic must push `GitWireFailure` and enter `FailedPhase`.
Readable input after completed or failed state must be rejected.

## Non-Goals

Do not implement:

- construction of v2 requests;
- fetch negotiation policy;
- repository or ref visibility;
- pack parsing, indexing, or object validation;
- packfile URI downloading;
- `sideband-all`;
- buffering a full response or packfile in a second byte array.

Preserve unknown `ls-refs` attributes, but reject unknown fetch section headers
until a typed implementation is added.

## Required Tests

Follow strict red-green-refactor cycles.

### `ls-refs`

- ordinary ref list;
- symref, peeled tag, and unborn ref;
- unknown attribute preservation;
- fragmented header, payload, and terminal packets;
- duplicate ref and duplicate known attribute;
- malformed object id and missing attribute value;
- response-end before flush;
- close before response-end.

### `fetch`

- `NAK` negotiation response;
- multiple ACKs plus `ready`;
- rejection of mixed ACK and NAK;
- shallow and unshallow rows;
- wanted refs;
- optional section combinations in legal order;
- duplicate, unknown, and out-of-order sections;
- malformed rows with packet diagnostics;
- side-band pack data split across packets and input chunks;
- progress delivery and fatal band failure;
- lazy raw-target creation;
- side-band flush followed by response-end in the same input buffer;
- close during an incomplete side-band packet;
- proof that the final model retains no pack payload.

## Verification

During development:

```bash
mvn test -Pdev -q -pl core/git-parser -am \
  -Dtest=GitLsRefsResponseMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn test -Pdev -q -pl core/git-parser -am \
  -Dtest=GitFetchResponseMachineTest,GitSideBandTest,GitMinimalWireMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn test -Pdev -q -pl core/git-parser -am
```

Before completion:

```bash
git diff --check
mvn test -Pdev -q -pl core/git-parser -am -Dtest=GitWireCoreBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn verify -Pdev
```

All Maven commands must run outside the sandbox.

## Acceptance Criteria

- Both parsers are chunk-driven modes of `GitMinimalWireMachine`.
- No complete response buffer is required.
- Semantic intermediate values move through the typed machine stack.
- `ls-refs` returns ordered refs with typed known attributes and preserved
  unknown attributes.
- `fetch` returns typed section metadata and streams band-one pack bytes.
- Flush, delimiter, and response-end transitions are explicit and validated.
- Malformed responses produce inspectable `GitWireFailure` outcomes.
- Existing callback mode, v1 advertisement behavior, and side-band tests remain
  compatible.
- Focused tests and `mvn verify -Pdev` pass.

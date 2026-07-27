# Git Wire Response Parsers Design

## Goal

Extend `GitMinimalWireMachine` with streaming protocol state for:

- protocol v1 reference advertisements;
- protocol v2 `ls-refs` responses;
- protocol v2 `fetch` responses through the packfile side-band entry point.

The machine must accept arbitrarily fragmented `ByteBuf` input, retain only
bounded structured protocol data, and forward pack data without accumulating it
in the parsed response.

## Architecture

`GitMinimalWireMachine` remains the single owner of pkt-line framing, fragmented
payload storage, input-buffer ownership, and raw forwarding. Typed parsing modes
add protocol-specific phases to the existing machine instead of introducing a
parallel session API or parsers that require a complete response buffer.

The machine has two distinct kinds of state:

- its current wire phase, such as control-header reading, structured-payload
  reading, or raw forwarding;
- an internal operand stack containing completed semantic values produced by
  protocol phases.

A protocol phase consumes pkt-line events until it has built one meaningful
value. It pushes that typed value onto the machine stack and transitions to the
next phase. The next phase takes its inputs from the stack. Header fragments and
partial pkt-line payloads remain in the existing wire phases and never become
stack entries.

The stack accepts only a closed hierarchy of wire values. Typed stack operations
validate the expected input type so an invalid internal transition fails as a
programming error rather than being reported as malformed remote traffic.

The existing callback-based construction of `GitMinimalWireMachine` remains
available for current consumers. Typed factories or constructors select v1
advertisement, v2 `ls-refs`, or v2 `fetch` parsing without changing the
chunk-driven `accept(ByteBuf)` boundary.

## Protocol V1 Advertisement

The first advertisement line parses:

```text
<sha1> <ref-name>\0<capability-list>
```

Only the first line may contain the NUL-separated capability list.
`GitCapabilityParser` produces a `GitCapabilitySet`, which is pushed for the
following advertisement phase to consume.

Subsequent data packets contain ordinary refs or peeled tag rows ending in
`^{}`. A flush packet terminates the advertisement. The completed value contains
the capability set, ordinary refs, peeled relationships, and an explicit empty
repository flag.

The all-zero object id with ref name `capabilities^{}` is the empty-repository
sentinel. Duplicate refs, capabilities on later rows, malformed SHA-1 values,
peeled rows without their base ref, and a missing terminal flush fail the
machine.

## Protocol V2 Ls-Refs Response

Each data packet contains an object id, ref name, and zero or more attributes.
Known attributes receive typed representation:

- `symref-target`;
- `peeled`;
- `unborn`.

Unknown attributes are preserved as raw key/value data so extensions do not
require parser changes. A response-end packet terminates the response.
Malformed rows, duplicate refs, invalid known attributes, and other terminal
packets fail the machine.

## Protocol V2 Fetch Response

The fetch parser recognizes the ordered response sections:

- `acknowledgments`;
- `shallow-info`;
- `wanted-refs`;
- `packfile`.

Each completed structured section pushes its typed value for the next fetch
phase to consume. The accumulated result includes ACK/NAK and ready state,
shallow and unshallow object ids, wanted refs, received-section order, and
packfile-entry status.

Sections may not repeat or appear out of protocol order. The packfile section is
last. Entering it switches the machine to side-band/raw forwarding; pack bytes
go directly to the downstream target and are never stored in the response
model. A response-end packet completes the response.

## Completion and Errors

Successful parsing leaves one typed result at the top of the operand stack and
transitions to a completed phase. Results are unavailable before that phase.
Additional input after completion is rejected.

Malformed remote traffic pushes a `GitWireFailure` containing the existing
typed `GitWireError` and transitions to a failed phase. The failure records the
semantic phase, packet index, and byte offset without retaining raw pack
contents. Callers may inspect a success/failure outcome or use a result accessor
that converts the stored failure to `GitWireException`.

A correctly framed remote `ERR` packet is represented separately from malformed
traffic. Violations of internal stack types, impossible phase transitions, or
buffer ownership remain immediate programming exceptions.

Closing an incomplete machine validates the active wire and semantic phases.
The stack remains inspectable so diagnostics can identify the last completed
semantic value and the section that did not finish.

## Testing

Implementation follows test-driven development with focused cases for:

- typed operand-stack handoff between protocol phases;
- ordinary v1 advertisements;
- v1 capabilities, peeled tags, and the empty-repository sentinel;
- malformed, duplicate, incomplete, and arbitrarily fragmented v1 input;
- v2 `ls-refs` known and unknown attributes;
- v2 fetch acknowledgment, shallow-info, wanted-refs, and packfile sections;
- legal and illegal section transitions;
- side-band/raw pack forwarding without storing pack bytes;
- response-end completion, premature close, and failures retained on the stack;
- splits inside pkt-line headers and structured payloads.

Existing `GitMinimalWireMachine` behavior and tests remain valid. Focused module
tests run during each red-green-refactor cycle, followed by the repository's
routine `mvn verify -Pdev` check.

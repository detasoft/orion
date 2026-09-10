# Refactor Git report-status to blocking streaming parsing

Status: upcoming

- [ ] Replace accumulated receive-pack report-status parsing with one blocking,
  incremental parser for direct and side-band responses.

## Scope

- In `GitBlockingClientWire`, replace whole-response byte accumulation and
  intermediate `List<String>` parsing with immediate unpack/ref-status validation.
- Compose nested blocking reads: report-status parser → pkt-line reader →
  side-band channel-1 byte reader → outer pkt-line transport. Direct responses
  use the same report-status parser without the side-band layer.
- Handle inner headers and payloads split across outer packets, and multiple
  inner packets within one outer packet; retain only bounded buffering and the
  result/ref-validation state needed by the operation.
- Preserve size limits, channel-2 handling, fatal/unknown-channel errors, blank
  unpack rejection, valid negative unpack results, and expected-ref validation.
- Validate both inner and outer termination, including truncation and trailing
  data; preserve buffer ownership and session cleanup on success and failure.
- Remove the replaced accumulation helpers and duplicate pkt-line parsing path;
  do not retain a compatibility or fallback implementation.

## Verification

- Cover successful direct and side-band responses, fragmented inner headers and
  payloads, coalesced packets, and interleaved progress.
- Cover malformed/truncated responses, inner/outer flush boundaries, trailing
  data, size limits, fatal/unknown channels, and missing/duplicate/unexpected refs.

## Boundary

The transport is already blocking; this task changes report-status parsing, not
the transport execution model. Define and implement structured `packetIndex`
and byte-offset diagnostics separately after the reader layers are established.

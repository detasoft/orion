# Protocol V2 Fetch Design

## Goal

Replace the protocol v2 `FetchContinuation` placeholder with the smallest
useful native server fetch: parse the base fetch arguments already supported by
the native repository boundary and stream a standards-shaped v2 packfile
response.

## Scope

The continuation accepts `want`, `have`, `done`, `thin-pack`, `ofs-delta`, and
`no-progress`. It rejects malformed object ids, duplicate `done`, requests
without wants, unknown arguments, and requests that end without `done`.

`thin-pack` and `ofs-delta` are preserved in `NativeFetchRequest`. The current
no-delta pack builder may still return a complete non-thin pack because these
arguments permit optimizations rather than requiring them. `no-progress` is
accepted because the native producer currently emits no progress messages.

`include-tag`, shallow history, filters, ref-in-want, sideband-all,
wait-for-done, and packfile URIs remain future work. The server must advertise
plain `fetch`, not `fetch=shallow`, until shallow history and `shallow-info`
responses exist.

## Continuation Flow

`FetchContinuation` delegates each pkt-line header to
`ControlHeaderContinuation`. Data packets are consumed by a bounded payload
continuation and normalized into a fetch request. A flush after `done` starts
the repository fetch and v2 response. Delimiter and response-end packets are
invalid inside the request.

The response begins with `packfile\n`, then streams the existing
`NativePackProducer` through side-band channel 1, and ends with a flush packet.
Because `done` is required in this slice, the server omits the acknowledgments
section as required by protocol v2.

## Errors and Ownership

Wire validation failures complete through the existing typed continuation error
flow. Repository, serialization, and delivery failures complete through the
standard output result flow. The response owns and closes its pack producer;
input `ByteBuf` instances remain caller-owned.

## Tests

Tests cover a fragmented valid request, multiple wants and haves, accepted
optimization flags, exact v2 response framing, streamed output, malformed
object ids, missing `want` or `done`, unknown arguments, and unsupported
control packets. Advertisement tests assert that unsupported shallow support is
not advertised.

# Complete Shallow History Support

Status: complete
Source: split out from protocol v2 server fetch follow-up work.

Protocol v2 server fetch currently supports depth-based shallow fetch through
`deepen <depth>`. Complete shallow history support as a separate task so the
advertised shallow capability is backed by the rest of the expected shallow
request and response surface.

The finished behavior should accept and validate client shallow state and the
remaining deepen forms, report correct shallow and unshallow boundaries, and
keep object selection, pack generation, and protocol errors consistent when
shallow state is combined with haves, wants, wanted refs, object filters,
sideband-all, and packfile URI responses.

## Required Protocol Surface

- [x] `shallow <object-id>` client state is parsed and carried in fetch
  request metadata.
- [x] `deepen-since <timestamp>` is parsed and carried, then rejected as an
  unsupported deepening mode until full graph semantics are implemented.
- [x] `deepen-not <ref-or-revision>` is parsed and carried, then rejected as an
  unsupported deepening mode until full graph semantics are implemented.
- [x] `deepen-relative` is parsed and carried with depth-based shallow fetch.
- [x] `unshallow` response lines are supported by response metadata and
  protocol v2 serialization.
- [x] Full Git shallow graph semantics for time-based and ref-based deepening.

Completed 2026-09-01: implemented commit timestamp parsing, `deepen-since`,
`deepen-not` for `HEAD` and full refs, client shallow/unshallow boundaries, and
storage-layer shallow conflict validation.

## Acceptance

- Valid requests produce protocol v2 responses that match Git shallow semantics.
- Invalid, unsupported, or contradictory shallow requests fail through the
  standard protocol error path.
- Existing depth-based shallow fetch behavior remains compatible.
- Coverage includes fragmented request parsing and interactions with haves,
  object filters, sideband-all, wanted refs, and packfile URI responses.

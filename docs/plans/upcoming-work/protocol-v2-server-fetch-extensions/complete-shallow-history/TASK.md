# Complete Shallow History Support

Status: ready
Source: follow-up from current protocol v2 fetch review.

Protocol v2 server fetch currently supports depth-based shallow fetch through
`deepen <depth>`. Complete shallow history support so the advertised shallow
capability is backed by the rest of the expected shallow request and response
surface.

The finished behavior should accept and validate client shallow state and the
remaining deepen forms, report correct shallow and unshallow boundaries, and
keep object selection, pack generation, and protocol errors consistent when
shallow state is combined with haves, wants, wanted refs, object filters,
sideband-all, and packfile URI responses.

## Required Protocol Surface

- `shallow <object-id>` client state.
- `deepen-since <timestamp>`.
- `deepen-not <ref-or-revision>`.
- `deepen-relative`.
- `unshallow` response lines when previously shallow commits become complete.

## Acceptance

- Valid requests produce protocol v2 responses that match Git shallow semantics.
- Invalid, unsupported, or contradictory shallow requests fail through the
  standard protocol error path.
- Existing depth-based shallow fetch behavior remains compatible.
- Coverage includes fragmented request parsing and interactions with haves,
  object filters, sideband-all, wanted refs, and packfile URI responses.

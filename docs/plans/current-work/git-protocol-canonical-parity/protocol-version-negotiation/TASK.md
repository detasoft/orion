# Align Git Protocol Version Negotiation

Status: done
Source: 2026-09-01 canonical parity audit against `protocol.c`,
`builtin/upload-pack.c`, and `builtin/receive-pack.c`.

Match canonical version selection across daemon, SSH, and Smart HTTP. Accept
explicit v0, ignore unsupported version offers, and select the greatest known
version from repeated offers instead of retaining only one value.

Emit the required `version 1` advertisement marker for upload-pack and
receive-pack. Treat a receive-pack v2 offer as the canonical v0 fallback for
both discovery and request serving rather than failing the request.

Cover repeated and unknown offers, explicit v0, v1 discovery framing, and
receive-pack v2 fallback on each applicable transport.

Completed 2026-09-01: version offers select the greatest recognized value,
explicit v0 and v1 are supported, v1 advertisements carry their marker, and
receive-pack v2 offers fall back to the legacy protocol.

# Complete Receive-Pack Command And Status Handling

Status: done
Source: 2026-09-01 canonical parity audit against `builtin/receive-pack.c` and
the pack protocol.

Complete the advertised and parsed receive-pack surface, including
`report-status-v2`, delete and quiet signaling, shallow command prefixes, and
supported optional command sections. Do not advertise capabilities until their
wire behavior is implemented.

Return negotiated `unpack <error>` and per-ref `ng` results for expected pack,
validation, and publication failures instead of aborting the HTTP or transport
request. Cover delete-only and empty command sections, malformed packs,
side-band status framing, and both report-status versions.

Progress 2026-09-01: added `report-status-v2`, delete and quiet capabilities,
malformed-pack status reporting, and empty command-section handling while
preserving transport timeouts as I/O failures.

Completed 2026-09-01: receive shallow prefixes are validated and carried in
the command section, including ordering and malformed-ID coverage.

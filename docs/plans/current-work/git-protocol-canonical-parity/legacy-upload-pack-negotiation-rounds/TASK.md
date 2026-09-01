# Complete Legacy Upload-Pack Negotiation Rounds

Status: complete
Source: 2026-09-01 canonical parity audit against `upload-pack.c` and the pack
protocol.

Match canonical `multi_ack` and `multi_ack_detailed` round termination. A
`done` command must produce the final bare ACK for the last common object, not
an `ACK ... ready`; `ready` is a reachability decision made at a negotiation
round boundary and is followed by the canonical NAK framing.

Preserve negotiation state across rounds and cover no common objects, mixed
known and unknown haves, reachability that is and is not sufficient to stop,
multiple flush-delimited rounds, final `done`, and stateless request behavior.

Completed 2026-09-01: moved `ready` to reachable round boundaries, preserved
stateful rounds, and ended stateless rounds at flush with canonical framing.

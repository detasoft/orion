# Align Native Git Daemon Bootstrap Parsing

Status: complete
Source: 2026-09-01 canonical parity audit against `daemon.c` and the pack
protocol.

Accept a daemon request without optional NUL-delimited metadata. Parse the
optional host field with canonical positioning and case handling, reject
malformed extended arguments, and preserve protocol parameters after the host
field without treating arbitrary metadata as a last-value-wins map.

Cover metadata-free requests, empty and mixed-case host fields, malformed
first extended arguments, repeated protocol parameters, and packet payloads
with or without the optional trailing newline.

Completed 2026-09-01: aligned native daemon request framing, host parsing,
and ordered protocol parameter preservation with canonical Git daemon behavior.

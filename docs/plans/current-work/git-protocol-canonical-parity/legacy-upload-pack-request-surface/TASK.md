# Complete Legacy Upload-Pack Request Surface

Status: todo
Owner: codex, session 863d23b0, paused 2026-09-01 18:52 Europe/Amsterdam; next: enforce advertised wants and add the backed legacy capability surface after negotiation-round edits settle.
Source: 2026-09-01 canonical parity audit against `upload-pack.c` and the pack
protocol.

Bring legacy wants and advertised capabilities in line with implemented
behavior. Enforce canonical advertised-ref and configured unadvertised-object
rules, and support the legacy shallow/deepen request prelude before have
negotiation.

Advertise and honor only a coherent capability set, including the applicable
shallow, side-band, progress, tag, thin-pack, and delta options. Cover hidden
or unadvertised wants, missing objects, shallow and deepen combinations, and
capability-dependent pack output.

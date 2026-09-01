# Complete Legacy Upload-Pack Request Surface

Status: done
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

Completed 2026-09-01: wants are limited to advertised tips and peeled targets;
legacy shallow/deepen state is parsed, validated, returned before negotiation,
and applied to pack selection; the advertisement now includes the backed
shallow, progress, and tag capability surface.

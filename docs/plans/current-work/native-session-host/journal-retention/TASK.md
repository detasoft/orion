# Add Journal Segmentation, Compression, and Retention

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: completed journal core

Bound journal storage without changing the logical event stream or blocking PTY
output at the retention limit.

## Scope

- Rotate size-bounded segments and group records into independently recoverable
  blocks.
- Select measured defaults within 32-128 MiB per segment and 256 KiB-1 MiB of
  uncompressed records per block while keeping both configurable.
- Add block-level Zstandard compression while allowing an uncompressed active
  tail.
- Track first/last timestamps, record counts, lengths, codecs, and checksums in
  block framing.
- Enforce configurable maximum storage with `DROP_OLDEST` over closed segments.
- Maintain oldest/latest available timestamps and report a cursor gap after
  retained history has been deleted.
- Test segment and block boundaries, incompressible data, partial compressed
  blocks, concurrent readers, deletion failures, restart recovery, and a slow
  consumer falling behind retention.

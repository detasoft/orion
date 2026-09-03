# Add Journal Segmentation, Compression, and Retention

Status: todo
Detailed plan: ../../../2026-09-02-session-journal-cbor-sequence.md
Depends on: ../cbor-sequence-journal-format/TASK.md

Bound journal storage without changing the logical event stream or blocking PTY
output at the retention limit.

- [ ] Add journal segmentation, compression, and retention.
  - Owner: codex, session 32cbac98, started 2026-09-03 19:39 Europe/Amsterdam.

## Scope

- Rotate size-bounded `.cbor` segments only between complete CBOR items.
- Add Zstandard compression for closed segments while keeping the active
  segment as an uncompressed `.cbor` file.
- Preserve the exact logical CBOR Sequence after decompression without adding
  block framing, segment header records, a `FINAL` flag, or an equivalent
  completion marker.
- Enforce configurable maximum storage with `DROP_OLDEST` over closed segments.
- Derive `firstAvailableEventId` from the first record of the oldest retained
  segment and report an event cursor gap after history has been deleted.
- Treat any segment-to-first-event index as rebuildable cache rather than
  journal state.
- Test segment boundaries, incompressible data, partial active items,
  compressed-segment recovery, concurrent readers, deletion failures, restart
  recovery, and a slow consumer falling behind retention.

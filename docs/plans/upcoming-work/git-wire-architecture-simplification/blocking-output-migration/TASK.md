# Complete Blocking Git Output Migration

Status: todo
Plan: [Git wire architecture simplification](../../../2026-09-03-git-wire-architecture-simplification.md)

Remove the output objects that still model resumable serialization even though
blocking `BufferedByteOutput` writes now complete synchronously.

## Scope

- Delete `OutputSerialization`, `AsciiPacketSequenceSerialization`,
  `PacketListSerialization`, and `PktLineSerialization`.
- Replace their call sites with direct, ordinary writes without creating
  intermediate `List<String>` or `List<byte[]>` solely for serialization.
- Preserve the existing byte-for-byte tests for advertisements, acknowledgments,
  status reports, shallow information, and protocol v2 sections.
- Replace `LegacySideBandResponse`, `LegacyPackResponse`, and
  `ProtocolV2PackfileResponse` plus `advance()` with one-shot `send...` methods.
- Make each `send...` method close its `NativePackProducer` on success and on
  every write, flush, or runtime failure.
- Preserve chunked pack streaming and blocking backpressure.

## Non-Goals

- Do not broaden this slice into removal of other wire helpers or error types.
- Preserve programmatic wire-error classification because it remains useful
  for server logs and diagnostics even when clients do not consume it.

## Follow-Up Review

After implementation, rerun `architecture-simplifier` on the output path and
record any further safe removals as separate tasks; do not fold them into this
migration opportunistically.

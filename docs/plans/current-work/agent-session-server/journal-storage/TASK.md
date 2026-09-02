# Implement Durable Session Journal Storage

Status: todo
Depends on: completed server Agent protocol contracts
Journal contract: ../../../2026-09-02-session-journal-cbor-sequence.md

Store the authoritative replicated prefix of each raw session journal and make
the committed prefix the only source of resume cursors.

## Scope

- Define `SessionJournalStorage` operations for first and last event IDs,
  append, and `readAfter(sessionId, eventId)` without exposing segment layout.
- Implement filesystem storage with independently rotated CBOR Sequence
  segments and transparent reads of active `.cbor` and closed `.cbor.zst` files.
- Enforce strictly increasing per-session event IDs; ignore byte-identical
  duplicates and report a serious corruption error for conflicting duplicates.
- Make append durability and metadata publication atomic enough that
  `lastEventId` never includes records held only in memory or temporary files.
- Test empty journals, restart recovery, multi-batch append, duplicates,
  conflicting bytes, partial writes, rotation, compression, and ordered reads.

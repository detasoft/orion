# Keep One Paged AgentD Journal Reader

Status: todo
Depends on: completed AgentD incremental journal reader
Detailed plan: ../../../2026-09-04-agentd-journal-surface-simplification.md

Remove the unused whole-snapshot reader so recovery and live follow use one
bounded paged implementation with one set of journal semantics.

## Scope

- Keep `SessionJournalReader.readPage` as the only production read API. Use an
  empty physical position for an initial or restarted read from the server's
  authoritative cursor.
- Remove `readAfter`, `JournalReadResult`, the snapshot-only accumulator, and
  the duplicate scan/result path from AgentD.
- Stop bounded pages as soon as their record or byte limit is reached; do not
  scan the remaining journal merely to return an exact tail event ID.
- Return a terminal empty page for a retention gap. The caller must resolve the
  gap before requesting any retained records.
- Keep segment discovery, unsigned event ordering, raw/compressed segment
  handling, incomplete active tails, gaps, corruption, and stale-snapshot retry
  in the paged path.
- Replace availability trigger kinds and queued relative paths with one
  coalesced rescan wake-up plus a closed outcome.
- Remove `SessionJournalReader` if the remaining paged reader still has one
  internal consumer and one implementation after the API changes.
- Move whole-journal collection needed only by tests into test support instead
  of preserving a second production reader.
- Preserve the journal CBOR Sequence and replication wire format byte for byte.

## Acceptance

- AgentD has one production segment scan and result path, and no production
  caller depends on the removed snapshot API.
- Empty-position recovery and non-empty page continuation have equivalent gap,
  ordering, tail, corruption, and retry behavior.
- A page-limit result does not inspect later records or promise the current
  journal tail, and a gap result contains no journal records or resume position.
- Filesystem changes, overflow, polling, and watch re-registration wake the
  future pump without exposing their kind or path.
- Tests cover initial reads, bounded continuation across raw and compressed
  segments, retention races, incomplete active tails, gaps, and corruption.

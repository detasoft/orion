# Keep One Paged AgentD Journal Reader

Status: todo
Depends on: completed AgentD incremental journal reader

Remove the unused whole-snapshot reader so recovery and live follow use one
bounded paged implementation with one set of journal semantics.

## Scope

- Keep `SessionJournalReader.readPage` as the only production read API. Use an
  empty physical position for an initial or restarted read from the server's
  authoritative cursor.
- Remove `readAfter`, `JournalReadResult`, the snapshot-only accumulator, and
  the duplicate scan/result path from AgentD.
- Keep segment discovery, unsigned event ordering, raw/compressed segment
  handling, incomplete active tails, gaps, corruption, and stale-snapshot retry
  in the paged path.
- Move whole-journal collection needed only by tests into test support instead
  of preserving a second production reader.
- Preserve the journal CBOR Sequence and replication wire format byte for byte.

## Acceptance

- AgentD has one production segment scan and result path, and no production
  caller depends on the removed snapshot API.
- Empty-position recovery and non-empty page continuation have equivalent gap,
  ordering, tail, corruption, and retry behavior.
- Tests cover initial reads, bounded continuation across raw and compressed
  segments, retention races, incomplete active tails, gaps, and corruption.

# Replace the Session Journal with a CBOR Sequence

Status: todo
Detailed requirements: ../../../2026-09-02-session-journal-cbor-sequence.md
Depends on: completed journal core

Replace the existing framed journal contract with self-indexing CBOR Sequence
segments and one monotonic session-scoped event ID.

## Scope

- Encode every event as `[eventId, eventType, payload, ...futureFields]` with no
  segment header or framing outside CBOR.
- Remove the legacy block header and its `FINAL` flag. Do not introduce an
  equivalent completion flag or marker under another name; only a complete
  CBOR item establishes a record boundary.
- Replace separate timestamp, sequence, and cursor values with one strictly
  increasing `u64` `eventId` derived from the session monotonic clock.
- Discover segment ranges from the first event and implement
  `readAfter(eventId)` with retention-gap reporting.
- Recover complete items from an active segment with a partial trailing item
  and allow truncation to the last valid CBOR boundary.
- Keep segment indexes and first/last metadata optional and fully rebuildable
  from segment contents.
- Preserve forward compatibility for trailing record fields and unknown event
  types.
- Publish compatibility fixtures for session-host readers, AgentD replication,
  and server consumers.
- Update dependent journal, control, replay, and retention tests and contracts
  to use event IDs.

## Completion Criteria

- Writers and readers pass golden CBOR Sequence fixtures for the required PTY
  and process events.
- Event IDs remain unique and strictly increasing across equal clock readings,
  segment rotation, and journal recovery.
- Reading works across uncompressed and compressed segments without any
  persistent index or journal metadata.
- A partial trailing item loses only that item, and unknown compatible records do
  not prevent later records from being read.
- The encoded format and compatibility fixtures contain no `FINAL` flag or
  equivalent record/block completion marker.

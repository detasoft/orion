# Durable Session Journal Storage Design

Status: approved on 2026-09-03.

## Context

The central agent session server needs an authoritative durable prefix of each
session journal. AgentD may resend records after a disconnect, and the server
must not advance its resume cursor or let downstream consumers observe records
until those records have crossed a filesystem durability boundary.

The session host and server use the same logical CBOR Sequence record:

```text
[eventId, eventType, payload, ...optionalFutureFields]
```

`EventId` is ordered only within one session. `SessionId` is therefore context
for a journal, not a repeated field in every record.

## Module Boundary

Add a top-level `agent-session-server` Maven module that depends on
`agent-protocol`. The first task implements only the journal storage boundary
and its filesystem implementation. HTTP/2 handling, agent registries, live
event publication, and server assembly remain in their own follow-up tasks.

The server stores one journal per session:

```text
<journal-root>/
    <sessionId>/
        00000001.cbor.zst
        00000002.cbor.zst
        00000003.cbor
```

This mirrors the session host organization. The directory, storage API call,
and authenticated session stream carry `SessionId`; the encoded record does
not. As a result, AgentD can replicate the original record bytes without an
envelope or lossy reserialization.

## Storage Contract

`SessionJournalStorage` exposes:

- the optional first and last durable event IDs for a session;
- `append(sessionId, records)` for an ordered batch of decoded
  `SessionEventRecord` values;
- `readAfter(sessionId, cursor)` for a stable committed snapshot after an
  optional cursor.

The storage writes `SessionEventRecord.encodedRecord()` exactly as received.
An append result contains the maximum durable event ID and the records that
were newly stored by that call. The latter lets a future live-event broker
publish only newly committed records rather than duplicate retries.

A read result contains the ordered records in its snapshot and an explicit
retention gap when the requested cursor predates the first available event.
The initial implementation returns an immutable result; later API layers may
page by the last returned event ID without changing journal semantics.

## Append and Durability

Operations for one session are serialized by a session-local lock. Different
sessions do not share an append lock and may write and synchronize in
parallel.

Before writing, append validates the complete batch:

1. Event IDs are strictly increasing using unsigned 64-bit comparison.
2. Existing records in an overlapping retry are compared by their complete
   encoded bytes.
3. Byte-identical duplicates are skipped.
4. Reuse of one event ID with different bytes is reported as serious
   corruption.
5. Every new event ID is greater than the current durable last event ID. Gaps
   between time-derived event IDs are valid.

Records are appended without being re-encoded. Rotation occurs only between
complete CBOR items. If a single item exceeds the configured segment target,
it occupies an oversized segment rather than being split.

After writing a batch, the storage forces every changed segment with
`FileChannel.force(true)`. Creating a session directory or segment also
requires the corresponding directory durability barrier. Only after all
required barriers succeed does the storage publish the new in-memory segment
catalog and return the new durable event ID.

If a write or force operation fails, append returns no success result and the
session writer is poisoned until it is reopened. The caller sends no durable
confirmation for that batch. A retry after reopening is safe whether none,
some, or all complete records survived the uncertain I/O outcome.

## End-to-End Confirmation

The storage result is the server-side durability boundary for replication:

```text
session-host journal
    -> AgentD
    -> server append
    -> filesystem durability barrier
    -> SESSION_SYNC(sessionId, durableEventId)
    -> AgentD
    -> ACK_JOURNAL(durableEventId)
```

The server may send `SESSION_SYNC` again whenever the durable high-water mark
advances. The same message supplies the initial resume cursor after opening or
reopening a stream, so no separate persisted acknowledgement cursor is needed
in AgentD or on the server. AgentD may acknowledge the session host only up to
the last server-confirmed event ID.

If confirmation is lost, AgentD resends the affected records. The server
recognizes identical duplicates and confirms the same durable cursor again.
The replication and session-host control tasks will implement this transport
behavior; the storage task supplies the required append result.

## Segment Catalog and Reads

Segment files are the only mandatory source of truth. On first access to a
session, storage scans its directory and derives an in-memory catalog with the
segment number, first and last event IDs, representation, and durable byte
boundary. No cursor, first/last-event metadata, or persistent index is needed
for correctness.

`readAfter` takes a snapshot of immutable closed segments and the durable
length of the active segment. It then reads that snapshot without retaining
the append lock. Records appended after the snapshot are returned by a later
read. A reader retries catalog resolution when compression replaces a path
between snapshot creation and opening the file.

The first available segment may have a number greater than one after future
retention. A missing numeric segment after the first available segment is
corruption rather than retention.

## Compression

Only closed segments are compressed. Compression runs as background
maintenance and is not part of the replication acknowledgement critical path.
An uncompressed closed segment remains a valid readable representation until
replacement finishes.

Replacement follows this sequence:

1. Stream the closed `.cbor` file into a temporary Zstandard file.
2. Finish and force the temporary file.
3. Validate that decompression yields the same logical CBOR Sequence and event
   range.
4. Atomically publish the `.cbor.zst` path and synchronize the directory.
5. Update the derived segment catalog.
6. Delete the original `.cbor` and synchronize the directory again.

If atomic publication is unavailable or any earlier step fails, maintenance
reports the failure and retains the original `.cbor`. It may retry later.
After a crash, a valid published `.cbor.zst` wins when both representations
exist; an invalid compressed copy is discarded only when the original
uncompressed copy is still valid. An invalid sole representation is stored
corruption.

The maintenance queue is bounded and coalesces work per session. Falling
behind leaves more closed `.cbor` files but never blocks append or weakens
durability.

## Recovery

Opening a session validates segment naming, ordering, CBOR structure, and
strictly increasing unsigned event IDs across segment boundaries. Unknown
event types, encoded payloads, and optional trailing fields are retained
unchanged.

Only the highest active `.cbor` segment may end in an incomplete CBOR item.
Recovery truncates that file to its last complete item before allowing more
appends. An incomplete item in a closed or non-final segment and structural
corruption inside a complete item are fatal for that session.

Recovery derives the durable first and last IDs from the remaining complete
records. A complete record that survived an append whose confirmation was
lost becomes part of the recovered durable prefix and is recognized as an
identical duplicate on retry.

## Failure Isolation

Storage failures fall into four categories:

- invalid append: a malformed or incorrectly ordered input batch;
- conflicting duplicate: one session/event identity has different bytes;
- stored corruption: an existing segment cannot be trusted;
- I/O failure: reading, writing, forcing, compression, or replacement fails.

Invalid input does not modify storage. Append I/O failure and stored
corruption poison only the affected session handle. Compression failure leaves
the uncompressed durable segment usable. Other sessions continue to append and
read normally.

## Verification

Tests cover:

- empty journals, first append, multiple batches, and restart recovery;
- exact preservation of unknown events and future trailing fields;
- identical and conflicting duplicate records;
- unsigned event-ID ordering and valid non-consecutive IDs;
- partial writes and force failures without premature cursor publication;
- incomplete active tails and corruption before the tail;
- rotation boundaries, oversized records, and incompressible data;
- interruption at each compression replacement stage;
- reads before, within, and between segments plus a retained-prefix gap;
- concurrent appends to separate sessions;
- snapshot reads concurrent with append and compression.

Focused tests run through the repository `make run-test` target. Final
development verification uses `mvn verify -Pdev -T 4` outside the sandbox.

## Deferred Work

This task does not implement HTTP/2 replication, AgentD journal pumping,
session-host `ACK_JOURNAL`, retention deletion, semantic event projections,
clustered writers, or production object storage. It defines the durability
boundary those follow-up tasks consume.

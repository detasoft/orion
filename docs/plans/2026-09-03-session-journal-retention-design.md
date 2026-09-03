# Session Journal Segmentation, Compression, and Retention Design

Status: approved on 2026-09-03.

## Context

The session host already writes a headerless CBOR Sequence into numbered
`.cbor` files, recovers a partial active tail, reads across segment boundaries,
and understands manually created `.cbor.zst` closed segments. Production writes
still use one unbounded active segment, compression is not performed by the
writer, and no retention limit is enforced.

This design completes those storage mechanics without changing the journal
record format or making terminal output wait for compression or deletion.

## Decisions

Use one background maintenance worker owned by each `JournalWriter`. The writer
remains the only component that creates active segments and appends records.
The worker only processes segments the writer has already closed.

Synchronous compression inside `append` was rejected because CPU and storage
latency would delay PTY reads. A separate maintenance process was rejected
because it would add lifecycle and coordination protocols without improving the
current single-session ownership model.

## Configuration and CLI

`JournalConfig` gains:

- `segment_max_bytes`, defaulting to 64 MiB;
- `journal_max_bytes`, defaulting to 1 GiB.

The `session-host` command exposes the same values as decimal byte counts:

```text
--journal-segment-bytes NUMBER
--journal-max-bytes NUMBER
```

Both values must be positive and the journal limit must be at least the segment
limit. Duplicate values, malformed integers, zero, and an inverted pair are
command-line errors. The only implemented retention policy is `DROP_OLDEST`, so
the CLI does not expose a policy selector or compression tuning options.

## Rotation and Write Path

The writer encodes the complete CBOR item before deciding whether to rotate. If
the active segment is non-empty and the new item would make it exceed
`segment_max_bytes`, the writer flushes the current file, creates the next
numbered active `.cbor` file, and then appends the item. Rotation therefore
occurs before an append and cannot report an append failure after the record was
already accepted.

An individual item larger than the configured segment size occupies a segment
by itself and is never split. That segment is closed before the following item.
The newest segment remains uncompressed, including after normal host shutdown.

Creating the new active file is the publication boundary for a closed segment.
After successful rotation the writer sends the closed segment number to the
maintenance worker. Queue entries contain only segment numbers, so compression
lag cannot retain journal payloads in memory or block the append path.

The current metadata format remains internally consistent by publishing the
writer's current active segment number. Segment files remain the source of
truth; metadata is never required for discovery or recovery.

## Background Maintenance

One worker processes notifications sequentially and reconciles all eligible
closed segments in number order. A reconciliation pass:

1. removes abandoned compression temporary files;
2. resolves interrupted replacements that contain both raw and compressed
   copies;
3. compresses every closed raw `.cbor` segment;
4. enforces the physical journal size limit over published journal files.

Compression writes `NNNNNNNN.cbor.zst.tmp`, finishes the Zstandard stream, and
syncs it when `Durability::EveryRecord` is selected. It then atomically renames
the temporary file to `NNNNNNNN.cbor.zst`, syncs the directory when required,
and removes the source `.cbor`. The source remains available until a complete
compressed copy has been published.

A reader that observes both published names during the short replacement
window uses the raw `.cbor` copy. On recovery, equal copies are collapsed; an
invalid or mismatched compressed copy is discarded and rebuilt from the raw
source. Temporary files are never journal segments and are ignored by readers.

The worker retries unfinished maintenance on later notifications and during
normal shutdown. Compression and deletion failures are recorded but do not
make `append` fail. Normal shutdown waits for the worker and reports any final
maintenance failure after the session's journal writes have completed.

## Retention

Retention counts the physical sizes of published `.cbor` and `.cbor.zst`
segment files after replacement cleanup. When the total exceeds
`journal_max_bytes`, it deletes complete closed segments from the oldest end
until the total is within the limit or only the active segment remains.

The active segment is never deleted. Maintenance is asynchronous, so the
physical size may temporarily exceed the configured maximum while the worker
is behind. It may also remain above the maximum when the active segment alone
is larger than the limit. These cases preserve the write path and complete CBOR
items instead of blocking terminal output or deleting active data.

Deleting only a prefix preserves contiguous segment numbering among all files
that remain. `firstAvailableEventId` continues to come from the first record of
the oldest retained segment. A cursor below it receives the existing explicit
`RetentionGap` result.

## Reading During Maintenance

`read_after` continues to discover segment ranges from segment contents and
requires no persistent index. It tolerates the raw/compressed overlap used for
replacement. If a selected closed segment disappears between directory listing
and open because retention advanced, the reader retries from a fresh snapshot;
the new oldest first event then produces a retention gap when appropriate.

Readers that opened a segment before deletion can finish from that file handle
on platforms that permit unlinking open files. No reader lock is added to the
append path.

## Testing

Tests will cover:

- CLI defaults, explicit byte limits, duplicate values, malformed values, zero,
  and a journal limit below the segment limit;
- automatic rotation at item boundaries and one oversized indivisible item;
- exact logical bytes after compressing compressible and incompressible data;
- first-event discovery without decoding an entire compressed segment;
- recovery from temporary files and from both published copies at each
  replacement boundary;
- restart with raw closed segments awaiting compression;
- concurrent reads while compression replaces a segment;
- physical-size retention, deletion failure and retry, an active segment larger
  than the limit, and a reader that falls behind into a retention gap;
- unchanged CBOR fixtures and partial active-tail recovery.

Filesystem failure cases use a private maintenance I/O seam so tests can inject
deterministic rename, compression-publication, and deletion failures without
adding a test-only public API.

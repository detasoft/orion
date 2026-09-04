# Incremental AgentD Journal Follow Design

Status: approved on 2026-09-04.

## Context

The completed `FileSystemSessionJournalReader` correctly reads raw and
compressed journal snapshots, preserves opaque records, and reports retention
gaps and damage after a valid prefix. Its `readAfter` operation is deliberately
stateless: every call discovers segment starts and scans the selected segment
from its beginning.

That is correct for restart recovery but inefficient for a live session. A
cursor inside a long-lived active segment causes the same retained prefix to be
decoded after every append. The reader also has no bounded page contract or
filesystem wakeup primitive, so a caller cannot promise the agreed 100
millisecond fallback latency.

## Compatibility

Keep the existing `readAfter(Path, Optional<EventId>)` snapshot operation and
its result model unchanged. Existing tests and recovery callers continue to
receive the complete available suffix, range, gap, incomplete-tail flag, and
optional issue.

Add a separate bounded `readPage` operation. It accepts the same recovery
cursor, an optional disposable `JournalReadPosition`, and `JournalReadLimits`.
It returns a `JournalReadPage` with bounded records, the observed range, a next
position, and an explicit boundary: complete, page limit, incomplete active
tail, retention gap, or issue after a valid prefix.

The position is public but opaque except for its last event ID. Physical
segment number, representation, byte offset, file identity, and incomplete
item start remain implementation details. A position is valid only with the
same logical cursor that produced it.

## Incremental Reading

On the initial call or after AgentD restart, `readPage` discovers and scans from
the server cursor just as `readAfter` does. For a valid position in a raw
segment, it seeks to the saved complete-record boundary rather than decoding
the segment prefix again.

If the previous call ended on a partial active item, the saved offset points to
that item's start. A later call rereads only the partial item plus newly
appended bytes. The reader publishes the item only after it becomes complete.

A rotation may leave the positioned raw segment closed; the reader consumes
any remaining complete bytes and crosses to the next segment. Compression,
replacement, truncation, or retention invalidates the physical hint. The
reader then rediscovers by the last logical event ID, retries at most once for
a disappearing file, and reports a gap if the retained floor moved past it.

Compressed segments are immutable and not seekable by decoded offset. A page
that resumes inside one may reopen and decompress through its saved decoded
offset. This affects historical catch-up only; the live active segment is raw
and directly seekable.

Page limits bound retained records and encoded record bytes. Scanning may
continue without retaining later records to establish the existing
`lastAvailableEventId` contract. Consequently, memory is bounded, while a page
cut inside a historical segment can cause repeated decompression work. The
steady-state tail path neither rescans nor retains the old active prefix.

## Range and Boundaries

An absent cursor means the server has no replicated prefix and never creates a
false gap. A present cursor below the first available event produces a gap
boundary. No replacement cursor is guessed.

Valid records preceding corruption or an I/O issue remain in the page. The
position never advances beyond the last returned complete record. An
incomplete item is tolerated only at the newest raw tail. Unknown event types,
encoded payloads, and trailing fields remain byte-for-byte opaque.

Both snapshot and page reads use `AgentProtocolLimits.journalDefaults()`. This
accepts a native maximum 16 MiB payload plus record overhead while structural
validation still rejects binary or text components above their own limits.
Ordinary Agent frames remain capped at 16 MiB.

## Availability Monitoring

Add `JournalAvailabilityMonitor`, an `AutoCloseable` waiter over one journal
directory. `await()` returns immediately for create, modify, delete, or
overflow notifications. `WatchService.poll` uses a default 100 millisecond
timeout, so missed or coalesced notifications still cause a periodic wakeup.

The monitor owns no worker thread and no cursor. Journal synchronization calls
`await()` from its existing per-session operation loop and then invokes
`readPage`. Invalid watch keys trigger bounded re-registration attempts;
closing the monitor unblocks a waiting call.

## Dependencies

Retain the completed reader's direct `zstd-jni` dependency and current managed
version. Apache Commons Compress is unnecessary because production code uses
`ZstdInputStream` directly. Rebase-introduced duplicate Zstandard declarations
must be removed.

## Verification

Tests extend the completed reader suite and cover page record/byte boundaries,
active raw offset reuse, completion of a partial item, rotation, compressed
fallback, retention invalidation, and restart without a position. A counting
input seam proves the active prefix is not reread.

Monitor tests use a fake event source for every trigger and watch-key recovery,
plus a real-directory append test with a generous deadline. The default poll
interval is asserted as 100 milliseconds without relying on exact scheduler
timing.

# Session Journal CBOR Sequence Format

## Decision

Store each session journal as numbered segments containing a CBOR Sequence.
Each segment is a sequence of independent CBOR items without a segment header,
block header, length prefix, or framing outside CBOR itself.

The format has no `FINAL` flag or equivalent completion marker. A record is
complete exactly when its CBOR item is complete; writers and readers must not
reintroduce a separate persisted completion state under another name.

This document supersedes the journal framing, timestamp, cursor, segmentation,
compression, retention, and recovery requirements in
`2026-09-01-native-session-host.md`. The remaining session-host architecture
and process-host requirements continue to apply.

## Record Format

The base record is a CBOR array:

```text
[eventId, eventType, payload]
```

- `eventId` is an unsigned 64-bit integer that is unique and strictly
  increasing within one session.
- `eventType` is an integer event-type identifier.
- `payload` is a CBOR value whose schema is selected by `eventType`.

The initial event payloads include:

```text
PTY_OUTPUT:     [eventId, PTY_OUTPUT, byte-string]
PTY_RESIZE:     [eventId, PTY_RESIZE, [cols, rows]]
PTY_INPUT:      [eventId, PTY_INPUT, [commandId, byte-string]]
PROCESS_EXITED: [eventId, PROCESS_EXITED, [exitCode]]
```

The contract work must assign and freeze stable integer IDs for the supported
event types and publish golden encoded fixtures. Terminal bytes remain opaque
and are stored without text conversion.

## Event ID

Separate journal `sequence` and `timestamp` fields are removed. One `eventId`
serves as record identity, total order, approximate session-relative time, and
journal cursor.

Generate it from a monotonic clock relative to the session start:

```text
raw = monotonicTimeSinceSessionStart()
eventId = max(raw, previousEventId + 1)
```

The value is not a Unix timestamp and must not be compared between sessions.
Recovery must read the last complete event and ensure that the next generated
ID remains greater even if the recovered clock reading is equal or lower.

## Segment Layout and Rotation

A session journal uses monotonically numbered files:

```text
session/
    00000001.cbor
    00000002.cbor
    00000003.cbor
```

The file name establishes segment order and does not encode an event ID. Each
file contains `CBOR Sequence<SessionEvent>`, and its first item is an ordinary
session event.

The active session appends to the current `.cbor` segment. After the configured
size threshold is reached, the writer closes it and creates the next numbered
segment. A CBOR item is never split between segments, and every new non-empty
segment begins with one complete record.

## Segment Ranges and Reading

The journal is self-indexing. A reader determines a segment's start by decoding
its first complete item and extracting `firstEventId`.

`readAfter(requestedEventId)` must:

1. List journal segments in segment-number order.
2. Decode the first record of every segment.
3. Find the last segment whose `firstEventId` is less than or equal to the
   requested ID.
4. Start with that segment, or with the oldest segment when no such segment
   exists.
5. Skip records whose `eventId` is less than or equal to the requested ID.
6. Return every later record and continue through following segments.

If the requested ID is lower than the first available event ID, the reader
reports a retention gap rather than an ordinary empty result.

## Index and Source of Truth

Persistent indexes are not required for correctness. An implementation may
cache the mapping from segment number to `firstEventId` when segment counts
justify it, but that index:

- may be deleted or damaged;
- must be rebuildable from the segment files;
- must never override values decoded from segment contents;
- is not required to open or read the journal.

The segment files are the only mandatory source of truth for the first and last
available events, segment ranges, record order, and cursor behavior. Separate
timestamp, sequence, cursor, and first/last event metadata is not journal state.

## Retention

Journal maximum size and retention policy are configurable. When the limit is
exceeded, delete the oldest fully closed segments without blocking writes to
the active segment.

After deletion, `firstAvailableEventId` is obtained from the first record of
the oldest remaining segment. Readers whose cursor predates that value receive
a gap result.

## Compression

Compression applies only to closed segments:

```text
00000001.cbor.zst
00000002.cbor.zst
00000003.cbor
```

Decompressing a closed segment must produce the same logical CBOR Sequence.
Compression must not add logical journal records or change record encoding. To
discover `firstEventId`, a reader only needs to decompress enough data for the
first complete CBOR item.

The active segment remains uncompressed. Replacement of a closed `.cbor` file
with its `.cbor.zst` form must be recoverable without losing the only valid
copy.

## Crash Recovery

Abnormal termination may leave the active `.cbor` segment ending in a partial
CBOR item. A reader must return every preceding complete item, ignore the
incomplete trailing item, and must not classify the whole segment as corrupt.
No flag written before or after an item participates in this decision.

Recovery may truncate the active file to the last valid CBOR boundary before
appending. Corruption inside an already completed item or before the trailing
item remains a journal error and must not be silently treated as an incomplete
tail.

## Forward Compatibility

Future records may append fields:

```text
[eventId, eventType, payload, ...optionalFutureFields]
```

The minimum record length is three. Readers must interpret the first three
positions, ignore unknown trailing positions, and preserve record boundaries.
Readers must surface an unknown `eventType` and its payload as an opaque record
and continue with later records. Consumers may skip semantic interpretation of
that opaque record. New fields may only be appended; the meaning of existing
positions cannot change.

## Replication

The same logical CBOR Sequence is used when AgentD replicates a session journal
to the server:

```text
session-host -> CBOR segments -> agentd -> HTTP/2 stream -> CBOR Sequence -> server
```

AgentD should preserve original encoded records where practical and must not
require conversion to another event format. Transport-level chunking may split
bytes arbitrarily, but it cannot change CBOR item boundaries or logical record
contents after reassembly.

## Verification

The format change is complete when tests cover:

- golden bytes and round trips for the required event payloads;
- monotonic event IDs when clock readings repeat and after recovery;
- multiple items without external framing and rotation only between items;
- `readAfter` before, within, and between segments;
- gap reporting after retention deletes old segments;
- rebuilding an optional index from uncompressed and compressed segments;
- discovery of the first event without decompressing an entire large segment;
- a partial active tail and truncation to the last complete boundary;
- absence of `FINAL` or any equivalent persisted completion marker in encoded
  fixtures and writer output;
- additional trailing fields and unknown event types;
- unchanged logical records across session-host, AgentD, and server fixtures.

No mandatory segment metadata file, timestamp index, sequence index, cursor
index, or persistent first/last event metadata may be required for correctness.

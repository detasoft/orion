# AgentD Session Journal Reader Design

Status: approved on 2026-09-04.

## Context

The AgentD task originally described the superseded framed journal proposal:
segment and block headers, length prefixes, checksums, compressed blocks, and a
timestamp cursor. The native session host now persists journal version 1 as a
headerless CBOR Sequence in numbered files. Closed segments may be compressed
as whole Zstandard streams and the newest segment is an uncompressed active
tail.

AgentD already depends on the shared `agent-protocol` module. That module owns
the unsigned `EventId`, bounded incremental `SessionEventDecoder`, and
`SessionEventRecord`, which retains the exact encoded payload and record. The
reader should reuse these contracts instead of introducing another CBOR parser
or interpreting event payload semantics.

## Decision

Implement a streaming filesystem reader in `agentd.journal`. It discovers a
fresh segment snapshot for each read, opens raw segments directly and closed
compressed segments through a bounded Zstandard stream, and feeds bytes into
the existing `SessionEventDecoder`.

Do not load a complete segment into memory and do not port the Rust CBOR parser
to Java. Memory remains bounded by the decoder's record limit and small I/O
buffers. The original `SessionEventRecord` is returned unchanged so later
sync and command consumers can independently forward or interpret it.

## Public Model

`SessionJournalReader.readAfter(sessionDirectory, optionalCursor)` returns a
typed snapshot containing:

- every record when the cursor is empty, otherwise records whose unsigned
  `eventId` is strictly greater than the cursor;
- optional first and last available event IDs;
- an optional retention gap containing the requested and first available IDs;
- whether an incomplete active tail was ignored;
- an optional terminal issue after the returned valid prefix.

The terminal issue distinguishes filesystem, segment-layout, decompression,
CBOR-structure, record-validation, event-order, and size-limit failures. This
lets AgentD retain every complete record preceding damage while still marking
the session degraded. An issue is never silently converted into an incomplete
tail.

An empty cursor represents the protocol's initial `SESSION_SYNC null`; it is
distinct from every numeric event ID and never produces a retention gap. An
empty journal has neither first nor last ID. Event ID comparisons always use
the existing unsigned `EventId` ordering; Java signed `long` ordering is never
used for cursor or monotonicity decisions.

## Segment Discovery and Cursor Reads

Recognize only positive, eight-digit `NNNNNNNN.cbor` and
`NNNNNNNN.cbor.zst` names. Ignore temporary and unrelated files. Sort by the
numeric segment number and require the retained sequence to be contiguous.

During asynchronous compression, raw and compressed forms may coexist for one
number. Prefer the raw form because it is the source retained until the
compressed replacement is fully published. The highest-numbered raw segment is
the only active segment; every other selected segment must contain only
complete CBOR records.

Read the first complete record of each segment to discover its first event ID.
Choose the last segment whose first ID is at most the requested cursor, or the
oldest segment if none qualifies. Scan that segment and every later segment,
validate strictly increasing nonzero event IDs, and retain records strictly
after the cursor. The scan also establishes the last available ID.

When the cursor is below the first available ID, return the gap together with
the available snapshot. Journal synchronization decides whether to pause or
send those records; the reader does not encode server policy.

## Tail Recovery and Corruption

A structurally incomplete final CBOR item is ignored only in the active raw
segment. All complete records before it remain available and the result marks
that the crash tail was ignored.

An incomplete closed or compressed segment is corruption. Invalid CBOR,
invalid mandatory record fields, zero or non-increasing event IDs, an invalid
Zstandard stream, and configured-limit violations are terminal issues. The
reader returns the complete validated prefix followed by the issue and does not
scan for a plausible later boundary.

Unknown event types, encoded payloads, and optional trailing fields are not
corruption. The reader validates only the common record envelope and preserves
the complete original bytes.

## Concurrent Maintenance

Session-host append, compression replacement, and acknowledged retention may
race a read. Appends after the reader reaches its current EOF are observed by a
later cursor read; watcher handoff belongs to journal synchronization.

If a selected segment disappears between discovery and open, repeat the whole
operation once from a fresh snapshot. The new snapshot may then produce a
retention gap. Other I/O failures are returned as terminal per-session issues;
they do not stop AgentD or affect other sessions.

The reader is stateless. AgentD restart reconstructs all ranges and cursor
results from journal files and never persists its own authoritative cursor or
index.

## Verification

Tests use the frozen cross-language CBOR fixtures and generated segment files.
They cover raw and compressed segments, record and rotation boundaries,
unsigned event IDs, cursor positions, empty journals, retention gaps, raw and
compressed overlap, incomplete active tails, corrupt or incomplete closed
segments, damaged Zstandard streams, non-monotonic IDs, unknown records and
trailing fields, replacement and retention races, concurrent appends, and
stateless restart reads.

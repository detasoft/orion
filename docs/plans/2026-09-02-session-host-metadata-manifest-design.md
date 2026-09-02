# Session Host Metadata Manifest Design

## Context

`session-host` currently stores journal identity and position in both the
journal and the atomically replaced `metadata` JSON file. Every append updates
the metadata timestamp bounds, and every PTY output read rewrites the complete
JSON file while holding shared host state.

The journal is already the durable ordered source of event history. Keeping a
second immediately synchronized durable representation adds write amplification
and still cannot make the journal append and metadata replacement atomic.

## Decision

Treat `metadata` as a session manifest with a small terminal-size snapshot. It
is not a journal index or lifecycle record and is never authoritative for
journal identity, segment discovery, retention bounds, or the latest record.

Remove these fields from metadata:

- `journalId`;
- `activeSegment`;
- `oldestAvailableTimestamp`;
- `latestTimestamp`;
- `state`.

Journal readers obtain the journal identifier and record ranges from segment
contents. They discover active and historical segments from the session
directory. The live host obtains current journal bounds directly from its
writer state when serving status; offline readers obtain them from journal
files.

Metadata continues to contain:

- format versions and session identity;
- creation and start times;
- command, working directory, and terminal environment;
- host and child process identifiers;
- initial terminal dimensions and the last successfully applied dimensions;
- effective sandbox description;
- control endpoint description.

The existing internal-only metadata v1 fixture is updated in place. No legacy
reader or migration path is retained.

## Write Points

Write the complete metadata snapshot only when its remaining facts change:

1. Create the session manifest.
2. Record the root process identity after successful launch.
3. Record dimensions after a successful resize.

Do not rewrite metadata for PTY output, input, signals, harness events, journal
timestamp advancement, or journal flushes. Segment rotation and retention also
do not update metadata because segment state is discovered from journal files.

## Consistency and Failure Semantics

The journal remains authoritative after a crash. Metadata may contain the
previous resize snapshot when failure occurs between a journal append, an
external side effect, and metadata replacement. Readers that need the exact
event order or final dimensions reconstruct them from the journal.

An inability to update metadata at one of the defined write points is
reported as a host error. Metadata I/O is no longer on the PTY output or
ordinary control-event path, so a snapshot replacement failure cannot reject an
otherwise successful output, input, signal, or harness append.

## Status

A successful control response already proves that `session-host` is live, so
`STATUS` does not need a persisted lifecycle state or a host-live flag. If the
response retains journal bounds, it obtains them without reading metadata.
`JournalWriter` owns the in-memory first and latest timestamp for the journal it
is currently writing. This state is a cache of the writer's own append position,
not a second durable source of truth.

## Verification

Tests must cover:

- metadata fixture encoding and decoding without journal-derived fields;
- metadata remaining byte-for-byte unchanged across output, input, and signal
  journal appends while terminal dimensions are unchanged;
- metadata dimensions changing after a successful resize;
- live status reporting current journal bounds from writer state;
- journal reading and validation obtaining identity and bounds without
  metadata.

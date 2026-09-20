# Module Review: agent-session-server

## 2. Cursor reads retain full history before discarding its consumed prefix

**Problem.** readAfter(cursor) decodes and accumulates all retained records, then filters the prefix. Even a
caught-up follower materializes complete history to return nothing. HTTP follow repeats reads after change
notifications and 30-second waits; memory scales with history and reader count despite a tiny result.

**Sources.** [Snapshot and filtering](src/main/java/pro/deta/orion/agent/server/journal/SessionJournal.java#L189),
[full retention](src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java#L239),
[existing selective retention](src/main/java/pro/deta/orion/agent/server/journal/SegmentReader.java#L1068),
[HTTP follow](../net/http-core/src/main/java/pro/deta/orion/transport/http/SessionEventsRoute.java#L163),
[cursor tests](src/test/java/pro/deta/orion/agent/server/journal/FileSystemSessionJournalStorageTest.java#L119),
and [full-history command consumer](src/main/java/pro/deta/orion/agent/server/command/SessionCommandService.java#L147).

**Documented behavior.** HTTP cursors are exclusive; storage returns raw committed history and verifies segment
bounds/digests. No requirement to retain discarded pre-cursor records in memory was found.

**Contract.** Preserve unsigned event ordering, exclusive cursors, snapshots, unknown records, compression and
integrity failures. Existing full-history callers must still receive complete results.

**Minimal repair.** Pass the cursor into the existing reader and retain only later records while decoding,
extending its DecodedRecords mechanism. Preserve integrity validation. Verify caught-up cursors and small
suffixes behind large prefixes, including compressed segments and concurrent append.

**Alternatives and consequences.** Catalog bounds could also skip old segments, but that changes detection of
corruption in skipped history and needs a contract decision. Pagination/streaming is larger API work, needed
separately to bound an initial full-history result; it is unnecessary to remove discarded-prefix retention.
No second cache, index or journal owner is justified. This minimal fix still decodes historical bytes.

**Confidence.** High in unnecessary retention; whether repeated whole-history corruption checking is intended
remains uncertain, and the minimal repair preserves it. No memory benchmark was run.

**Priority signals.** Importance: high for growing journals because allocation retains all historical payloads
per reader. Repair ease: medium using existing selective decoding while preserving integrity/snapshot tests.

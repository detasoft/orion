# Session Host Asynchronous Journal Retention Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Return `ACK_JOURNAL` after its watermark and ledger effects are durable while physical retention runs
asynchronously and scans only the oldest segment prefix needed for a deletion decision.

**Architecture:** Keep the existing single journal-maintenance worker as the sole segment mutator. Replace the
synchronous retention request/result exchange with fire-and-forget wake commands that the worker drains and
coalesces to the greatest active segment and acknowledged watermark before each reconciliation. Retention first
collects physical sizes, returns without decoding when already within the limit, and otherwise streams only the
oldest closed prefix until the size target or acknowledgement boundary stops deletion.

**Tech Stack:** Rust, `std::sync::mpsc`, CBOR Sequence journal segments, streaming Zstandard decoding,
Cargo tests, Maven reactor.

---

## Constraints

- Preserve the `ACK_JOURNAL` request/response bytes and its meaning: `ACCEPTED` confirms durable deletion
  permission, never completion of physical deletion.
- Preserve the versioned `control-retention-state` bytes and its durable publication order. Do not move the
  watermark into journal records, metadata, or a second authority.
- Keep one maintenance worker and one production retention path. Delete the synchronous result channel and old
  `apply_retention_through` API; do not add an alternate synchronous operation, compatibility wrapper, mode, or
  feature flag.
- Keep compression and deletion on the maintenance worker. An ACK must not wait for segment discovery,
  compression, decoding, deletion, directory synchronization, a prior maintenance failure, or a busy worker.
- A maintenance failure does not invalidate an already durable ACK. Retain the greatest watermark in worker
  state, record the latest reconciliation failure, and retry on a repeated/lower ACK, rotation wake, or finish.
- Active-segment snapshots may be stale only conservatively: maintenance may retain an extra closed segment, but
  must never delete the writer's current active segment. Coalescing therefore takes the maximum segment number.
- Preserve segment order, gap and corruption checks for every prefix actually considered for deletion. Do not
  decode later retained segments merely to validate them eagerly.
- Read compressed candidates through the existing bounded streaming decoder. Never materialize a whole
  decompressed segment in memory.
- Preserve all checked-in protocol and journal fixtures byte-for-byte.

## Task 1: Specify non-blocking and coalesced maintenance behavior

**Files:**

- Modify: `session-host/src/journal.rs`
- Test: inline tests in `session-host/src/journal.rs`

1. Add a test maintenance filesystem that can pause a reconciliation after the worker has started it and signal
   the test deterministically.
2. Add a red test that schedules acknowledged retention while the worker is paused, then immediately appends a
   journal record on the caller thread. The scheduling call and append must complete before maintenance is
   released.
3. Add a red test that queues repeated and increasing watermarks while maintenance is paused. After release, the
   worker must reconcile from the greatest watermark and must not lose the newest active-segment boundary.
4. Rewrite the existing transient-deletion test so a failed asynchronous attempt is retried by a repeated
   watermark instead of returning the deletion error to the ACK path.
5. Keep a persistent-failure test proving `finish_maintenance` surfaces the last unresolved maintenance error
   after retrying with the retained watermark.
6. Run `make session-host-test` outside the sandbox and record the expected failures before implementation.

## Task 2: Replace synchronous retention requests with one coalescing wake path

**Files:**

- Modify: `session-host/src/journal.rs`
- Test: inline tests in `session-host/src/journal.rs`

1. Replace `MaintenanceCommand::ApplyRetention { ..., result }` with one wake command carrying the observed
   active segment and an optional acknowledged watermark. Use the same command for rotation reconciliation and
   ACK-triggered retention so production has one scheduling path.
2. Start the worker with the initial active segment directly instead of enqueueing an initial command before the
   thread exists.
3. In `run_maintenance`, retain the maximum active segment and maximum acknowledged watermark. After receiving a
   wake, drain currently queued wakes with `try_recv`, fold their maxima, and run one reconciliation for that
   coalesced target.
4. Preserve finish ordering: fold every command queued before `Finish`, reconcile once with the newest target,
   join the worker, and return the final unresolved error only from `finish_maintenance`.
5. Replace `JournalMaintenance::apply_retention_through` with a non-waiting scheduling method. It may report a
   disconnected worker for diagnostics, but there must be no response receiver and no maintenance completion
   result in the ACK path.
6. Replace `JournalWriter::apply_retention_through` with the single asynchronous scheduling API and update all
   unit tests directly. Do not retain an alias for the removed method.
7. Run `make session-host-test` outside the sandbox.

## Task 3: Make ACK completion independent of physical cleanup

**Files:**

- Modify: `session-host/src/platform/unix.rs`
- Test: `session-host/tests/unix_process_host.rs`

1. Keep watermark validation, `JournalAcknowledgement::advance`, and operation-ledger acknowledgement inside the
   existing serialized `SharedState` section.
2. Schedule the durable watermark on journal maintenance without waiting, then explicitly release `SharedState`
   before constructing and returning the `ACCEPTED` response.
3. Treat scheduling failure as a maintenance diagnostic, not an ACK failure: the durable watermark remains valid
   deletion permission and physical cleanup may be retried or surfaced at host finish.
4. Update the existing ACK integration test to wait eventually for physical deletion instead of requiring it
   to have completed when the ACK frame arrives. Continue to assert the sidecar bytes and immediate ledger
   eviction.
5. Add deterministic coverage, using the narrowest test seam available, that a stalled maintenance attempt does
   not prevent a journal append and a non-retention control from completing. Do not introduce a production
   retention mode or test-only public API.
6. Run `make session-host-test` outside the sandbox.

## Task 4: Decode only the oldest deletion candidate prefix

**Files:**

- Modify: `session-host/src/journal.rs`
- Test: inline tests in `session-host/src/journal.rs`

1. In `enforce_retention`, discover the relevant segments and collect their physical sizes with checked
   arithmetic before scanning any record.
2. Return immediately without `scan_path` calls when the total is within `journal_max_bytes` or no durable
   acknowledgement watermark exists.
3. When oversized, visit closed segments from oldest to newest. Stream-scan one segment to obtain its last
   event ID, preserve strict order across the scanned prefix, and stop before deletion when that ID exceeds the
   durable watermark.
4. Delete and directory-sync an eligible segment, subtract its captured physical size, and stop scanning as soon
   as the retained total meets the size target. Never scan the active segment.
5. Delete `SegmentSize`, `retention_deletions`, and the production whole-journal `scan_segments` call. If
   `scan_segments` remains useful only for structural test assertions, compile it only for tests.
6. Add a scan-observation test seam local to the test module. Prove an in-limit journal performs zero record
   scans, an oversized journal scans only the deletion prefix, and a compressed noncandidate is never decoded.
7. Add candidate-prefix cases for an insufficient watermark, a corrupt candidate, a segment gap, an oversized
   active segment, size overflow, and reader/deletion races. Corruption outside the considered prefix must be
   deferred to a reader or later retention attempt rather than forcing a full scan.
8. Run `make session-host-test` outside the sandbox.

## Task 5: Document and verify the single asynchronous contract

**Files:**

- Modify: `docs/plans/2026-09-03-native-control-journal-idempotency-design.md`
- Modify: `docs/plans/2026-09-02-session-journal-cbor-sequence.md`
- Verify: `session-host/protocol/fixtures/`
- Verify: `session-host/src/journal.rs`
- Verify: `session-host/src/platform/unix.rs`

1. State that ACK completion ends after durable watermark publication, ledger update, and non-waiting
   maintenance scheduling. Physical retention is eventual, coalesced, retryable cleanup.
2. Document size-first planning and oldest-prefix-only decoding, including the conservative active-segment race
   rule and deferred validation of noncandidate segments.
3. Hash every checked-in `session-host/protocol/fixtures/*` file before implementation and compare after it; do
   not regenerate fixtures.
4. Search production code and confirm the result-bearing `ApplyRetention`, its one-shot channel,
   `apply_retention_through`, `SegmentSize`, and full retention pre-scan are absent.
5. Confirm the ACK handler performs no wait for maintenance and explicitly releases shared state before
   response.
6. Run `git diff --check` and the repository line-length check.
7. Run `make session-host-test` outside the sandbox.
8. Create the logical implementation commit, then run `make test` outside the sandbox as required by
   `AGENTS.md`. If a task-caused fix is needed after the commit, use the same commit subject so orchestration
   can squash the branch cleanly.

The review orchestrator owns final review, user approval, task-node deletion, squash, cherry-pick to `main`, the
post-integration `make test`, and branch/worktree cleanup.

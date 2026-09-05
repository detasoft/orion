# Session Host Explicit Journal Durability Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Replace the unused writer-wide durability mode with three explicit journal operations and make the
authoritative process-exit record survive a machine crash before normal host completion.

**Architecture:** A writer always uses buffered appends for ordinary traffic and explicit file synchronization for
authority records. Segment creation publishes the directory entry durably, and every rotation synchronizes the
closed prefix before publishing the next active segment. `finish_durably` is the only production path that emits
`PROCESS_EXITED`; it returns only after the final record is synchronized. No global durability enum, public flush
choice, or alternate finalization path remains.

**Tech Stack:** Rust, CBOR Sequence journal segments, Unix file and directory synchronization, Cargo tests, Maven
reactor.

---

## Constraints

- Delete the old APIs and update every in-repository caller atomically. Do not retain `Durability`, the
  `JournalConfig.durability` field, old `append`, deprecated aliases, adapters, compatibility shims, feature flags,
  or dual finalization paths.
- Preserve journal CBOR bytes, event ordering, segment names, protocol fixtures, control-wire fields, and metadata
  JSON bytes. Durability changes when bytes reach stable storage, not which bytes are encoded.
- A successful durable append guarantees that the appended authority record and its complete journal prefix are
  recoverable after machine crash. A successful final operation provides the same guarantee for the sole
  authoritative `PROCESS_EXITED` record, including when that append rotates to a new segment.
- Buffered writes may remain only in the active segment and may leave an incomplete crash tail. They must not call
  `sync_data` per record.
- Keep maintenance completion separate from `finish_durably`; termination coordination and control-connection
  shutdown remain owned by `termination-coordination`.
- Keep acknowledged retention synchronous for now. Its queue/result redesign belongs to
  `asynchronous-journal-retention`.
- The completed writer-API task node has been removed. Repair the selected task's dead dependency link to a plain
  completed-prerequisite statement in the isolated claim commit; do not recreate the removed task.

## Task 1: Specify explicit durability with failing tests

**Files:**

- Modify: `session-host/src/journal.rs`
- Test: inline tests in `session-host/src/journal.rs`

1. Extend the existing fault-injectable synchronization test seam only as far as needed to observe file data syncs
   and durable segment publication. Do not introduce a production strategy interface or configurable mode.
2. Add a buffered-append test proving an ordinary record performs no per-record `sync_data` while the active
   segment remains below its rotation threshold.
3. Add durable-append tests proving the record is synchronized and any buffered prefix is included in the same
   durable file boundary.
4. Add a rotation test proving a durable append synchronizes the old segment, durably publishes the new segment,
   and synchronizes the new record before returning.
5. Add `finish_durably` tests proving it emits the existing `PROCESS_EXITED` bytes, synchronizes them, and surfaces
   a final append or sync failure instead of reporting success. Include the final append crossing a segment
   boundary.
6. Add a deterministic failure test for the required sync step. Verify rollback removes an unaccepted final record
   and either permits a correct retry or poisons the writer when rollback itself cannot be made durable.
7. Run `make session-host-test` outside the sandbox and record the expected red result before implementation.

## Task 2: Delete the global durability model

**Files:**

- Modify: `session-host/src/journal.rs`
- Modify: `session-host/src/platform/unix.rs`
- Test: inline tests in `session-host/src/journal.rs`

1. Delete `Durability` and remove `durability` from `JournalConfig`, its default, validation, construction helpers,
   and all tests.
2. Rename the ordinary writer operation to `append_buffered(event_type, payload)`. Keep
   `append_durable(event_type, payload)` as the authority-record operation. Both must use one private append core;
   any internal traversal policy is not a public durability choice.
3. Add `finish_durably(exit_code)` and make it encode `PROCESS_EXITED` through the existing canonical encoder before
   applying the final durable barrier. Production must not construct the exit event through generic append plus
   flush.
4. Remove the public `flush` method. Make explicit rotation test-only if no production caller remains; automatic
   rotation is the sole runtime path.
5. Remove the durability parameter from `write_metadata`. Preserve the current production buffered write/rename
   behavior and do not advertise metadata machine-crash durability in this task.
6. Update every unit-test and Unix host caller directly. Do not add aliases for `append`, `flush`, or durability
   configuration.

## Task 3: Make segment boundaries support durable records

**Files:**

- Modify: `session-host/src/journal.rs`
- Test: inline tests in `session-host/src/journal.rs`

1. Publish each newly created segment's directory entry durably once. This removes the need to remember whether a
   later authority record is still inside an unpublished file.
2. Make every rotation synchronize the complete closed segment before creating and publishing its successor. The
   cost is per segment boundary, never per buffered PTY record, and it guarantees that a later durable record cannot
   survive without its ordered prefix.
3. For `append_durable` and `finish_durably`, write the record and call `sync_data` on the active file before
   advancing the accepted in-memory event ID. Reuse the existing truncation/seek/sync rollback behavior on failure.
4. Preserve automatic rotation between whole CBOR records. For a final append that rotates, enforce this order:
   synchronize the closed prefix, durably publish the new segment, write the exit record, synchronize the exit
   record, then return success.
5. Preserve maintenance compression ordering and crash-tail rules. Do not wait for compression or physical
   retention as part of a record durability barrier.
6. Run `make session-host-test` outside the sandbox.

## Task 4: Map every Unix call site to one semantic operation

**Files:**

- Modify: `session-host/src/platform/unix.rs`
- Test: `session-host/tests/unix_process_host.rs`

1. Use buffered append for PTY output, PTY input, resize, and signal records. Remove the `SharedState` append-plus-
   flush wrapper and name the remaining wrapper `append_buffered`.
2. Keep session start success/failure and command accepted/result records on `append_durable`; these are current
   authority and idempotency boundaries.
3. Replace the final `PROCESS_EXITED` append plus flush with `finish_durably(exit_code)`. Update in-memory status
   fields as before and propagate any final append/sync error out of `run_session`.
4. Keep `finish_maintenance` at the existing shutdown point. Do not change termination triggers, escalation,
   process reaping, connection draining, ACK behavior, or status wire fields.
5. Extend integration coverage to assert normal and signalled process completion still yield exactly one final
   authoritative exit event with unchanged encoded semantics.
6. Run `make session-host-test` outside the sandbox.

## Task 5: Document and verify the single durability contract

**Files:**

- Modify: `docs/plans/2026-09-02-session-journal-cbor-sequence.md`
- Verify: `session-host/protocol/fixtures/`
- Verify: `session-host/src/journal.rs`
- Verify: `session-host/src/platform/unix.rs`

1. Document the stable-storage ordering for buffered records, rotation, durable authority records, and the final
   exit barrier. State that durability applies to the complete prefix through the synchronized record.
2. Hash every checked-in `session-host/protocol/fixtures/*` file before implementation and compare after it; do not
   regenerate fixtures.
3. Search production code and confirm `Durability`, `JournalConfig.durability`, old generic `append`, public
   `flush`, and append-plus-flush `PROCESS_EXITED` are absent. Confirm each production append site names buffered,
   durable, or final semantics directly.
4. Confirm `Durability::EveryRecord` has not been replaced with a differently named global mode or boolean
   configuration.
5. Run `git diff --check`.
6. Run `make session-host-test` outside the sandbox.
7. Create the logical implementation commit, then run `make test` outside the sandbox as required by `AGENTS.md`.
   If a task-caused fix is needed after the commit, use the same commit subject for the follow-up so orchestration
   can squash the branch cleanly.

The review orchestrator owns final review, user approval, task-node deletion, squash, cherry-pick to `main`, the
post-integration `make test`, and branch/worktree cleanup.

# Session Host Metadata Manifest Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce `session-host` metadata to a stable session manifest and stop rewriting it for journal-only
events.

**Architecture:** Keep launch coordinates, process identity, terminal dimensions, sandbox policy, and the
control endpoint in metadata. Keep journal identity, segment discovery, retained bounds, and lifecycle facts
in their existing authoritative runtime or journal owners; build live `STATUS` fields from `SharedState` and
`JournalWriter` instead of the manifest.

**Tech Stack:** Rust 2024, Serde JSON, Unix PTY integration tests, Maven/Make-managed pinned Cargo toolchain.

---

### Task 1: Specify the reduced metadata contract

**Files:**
- Modify: `session-host/src/journal.rs`
- Modify: `session-host/src/protocol.rs`
- Modify: `session-host/protocol/fixtures/metadata-v1.json`

**Step 1: Write the failing tests**

- Replace the metadata round-trip fixture in `journal.rs` with a manifest that contains no journal UUID,
  segment, event-bound, or lifecycle fields.
- Assert that the serialized file omits `journalId`, `activeSegment`, `oldestAvailableEventId`,
  `latestEventId`, and `state` while retaining launch, process, terminal, sandbox, and control facts.
- Update the protocol fixture test to require the same reduced field set.

**Step 2: Run the tests and verify RED**

Run: `make session-host-test`

Expected: the new omission assertion fails against the current metadata serializer and checked-in fixture.

**Step 3: Implement the reduced value type and fixture**

- Delete `journal_id`, `state`, `active_segment`, `oldest_available_event_id`, and `latest_event_id` from
  `Metadata`.
- Delete the now-unused persisted `SessionState` enum and journal-ID validation.
- Keep validation for all remaining manifest fields.
- Update `metadata-v1.json` in place because metadata v1 is internal-only and readers ignore unknown fields.

**Step 4: Run the tests and verify GREEN**

Run: `make session-host-test`

Expected: metadata unit and fixture tests pass; Unix host compilation may identify remaining production call
sites that Task 2 must migrate before the full target is green.

### Task 2: Stop journal events from rewriting metadata

**Files:**
- Modify: `session-host/tests/unix_process_host.rs`
- Modify: `session-host/src/platform/unix.rs`

**Step 1: Write the failing integration test**

- Start a live PTY process, capture `metadata` after process identity publication, then produce output, input,
  and signal journal events without resizing.
- Assert that the metadata bytes remain unchanged while the events appear in the journal.
- Preserve and extend the existing resize assertion to prove a successful resize still changes the manifest.

**Step 2: Run the test and verify RED**

Run: `make session-host-test`

Expected: metadata changes after an ordinary journal event because `SharedState::append` updates journal
bounds and control handlers persist them.

**Step 3: Implement sparse manifest persistence**

- Construct initial metadata without journal or lifecycle fields.
- Persist once before launch, once after recording the child PID, and after each successful resize.
- Remove metadata mutation from `SharedState::append`.
- Remove metadata persistence from PTY output, input, signal, termination, and process-exit paths.
- Remove the obsolete journal UUID formatter and its unit test.

**Step 4: Run the tests and verify GREEN**

Run: `make session-host-test`

Expected: all Rust tests pass and metadata remains stable for non-resize events.

### Task 3: Keep live status authoritative without metadata journal fields

**Files:**
- Modify: `session-host/tests/unix_process_host.rs`
- Modify: `session-host/src/platform/unix.rs`

**Step 1: Add status coverage**

- Assert that live `STATUS` reports the first available and latest event IDs produced by the journal.
- Retain the current fixed 64-byte response and compatibility codes.

**Step 2: Implement status derivation**

- Derive the status lifecycle code from current host/process state rather than persisted metadata.
- Read oldest and latest event IDs from `JournalWriter::first_event_id()` and `latest_event_id()`.
- Continue returning live process coordinates and terminal dimensions from the in-memory manifest snapshot.

**Step 3: Run the tests**

Run: `make session-host-test`

Expected: all Rust tests pass, including live status bounds after journal events.

### Task 4: Align documentation and verify the complete change

**Files:**
- Modify: `session-host/protocol/README.md`
- Modify: `session-host/README.md` if it describes metadata ownership

**Step 1: Update documentation**

- Document metadata as a session manifest, list its remaining facts, and state its three write points.
- State that journal files own journal identity, segment discovery, and retained event bounds.
- State that live `STATUS` gets bounds from writer state and does not rely on persisted lifecycle state.

**Step 2: Check formatting and focused verification**

Run: `cargo fmt --manifest-path session-host/Cargo.toml -- --check`

Run: `make session-host-test`

Expected: formatting and all module tests pass.

**Step 3: Run project verification**

Run: `mvn verify -Pdev -T 4`

Expected: the complete development build passes.

**Step 4: Finish the dedicated worktree task**

- Review the diff against the approved metadata-manifest design.
- Squash task-branch commits into one commit named
  `Reduce session metadata to a manifest [task: native-session-host/metadata-manifest]`.
- Remove `docs/plans/current-work/native-session-host/metadata-manifest/` and its parent link in that commit.
- Cherry-pick the squashed commit to `main`, run `make test`, then remove the worktree and task branch.

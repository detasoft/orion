# Session Host Journal Writer API Simplification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Reduce the Rust journal runtime to one writer lifecycle and the minimum scanner needed by production,
while preserving persisted CBOR Sequence bytes and the cross-process protocol.

**Architecture:** A host incarnation creates exactly one `JournalWriter` and never resumes a failed writer. The
writer owns sequential event IDs and internally emits the only supported journal record shape. Production keeps a
small structural scanner for first/last event IDs, validation, crash-tail handling, and retention. Rich event
decoding and cursor reads move to integration-test support instead of remaining a runtime API.

**Tech Stack:** Rust, CBOR Sequence journal files, zstd-compressed closed segments, Cargo tests, Maven reactor.

---

## Constraints

- Delete the old Rust APIs and all in-repository callers in the same change. Do not add deprecated aliases,
  adapters, compatibility shims, feature flags, or a second writer/reader path.
- Preserve journal record and segment bytes. Record hashes for the checked-in files under
  `session-host/protocol/fixtures/` before implementation and compare them during final verification.
- Do not remove `payload_schema_version` or `flags` from the control wire protocol. Those fields in
  `session-host/src/host.rs`, `session-host/src/protocol.rs`, and protocol fixtures are outside this API cleanup.
- Preserve the signal payload's current `flags` field; it is not a legacy journal append argument.
- Leave `Durability::EveryRecord` for the dependent `journal-durability` task. This task may simplify callers but
  must not pre-empt the explicit durability redesign.
- Treat archived plans and reviews as history, not compatibility requirements. Update only active documentation
  that would otherwise describe failed-incarnation writer recovery as current behavior.

## Task 1: Establish the byte baseline and move integration reading to test support

**Files:**

- Create: `session-host/tests/support/mod.rs`
- Create: `session-host/tests/support/journal.rs`
- Modify: `session-host/tests/unix_process_host.rs`
- Test: `session-host/tests/unix_process_host.rs`

1. Hash every checked-in file in `session-host/protocol/fixtures/` and retain the manifest outside the source tree
   or in the task notes; do not regenerate fixtures.
2. Add a test-owned CBOR Sequence reader under `session-host/tests/support/`. It must expose only the decoded fields
   the Unix process-host integration tests assert: event ID, event type, payload, gap information if still needed,
   and crash-tail behavior.
3. Change `unix_process_host.rs` to use the test-support reader and event type. Continue importing production
   `journal::Metadata`; do not expose a test feature or hidden public reader from the library.
4. Cover a normal multi-record read and an incomplete active tail in the test-support module or existing
   integration tests.
5. Run `make session-host-test` outside the sandbox.

## Task 2: Remove failed-incarnation recovery and journal identity

**Files:**

- Modify: `session-host/src/journal.rs`
- Modify: `session-host/src/platform/unix.rs`
- Modify: `docs/plans/2026-09-02-session-journal-cbor-sequence.md`
- Test: inline tests in `session-host/src/journal.rs`

1. Change the sole production constructor to `JournalWriter::create(directory, config)`. Remove `journal_id` from
   `JournalWriter`, all constructors, and its accessor.
2. Delete `JournalWriter::recover` and every recovery-specific helper. Delete the Unix random journal-ID generator
   and update host startup to create a fresh incarnation directly.
3. Delete tests whose sole contract is resuming the old writer lifecycle. Where a retention or validation test only
   used recovery as setup, construct/rotate the required segments directly or call the narrow maintenance helper.
4. Keep meaningful crash-tail coverage as reader/scanner behavior: an incomplete final CBOR item in the active
   segment may be ignored, but no writer may reopen that segment for append.
5. Update the active journal-format document so event IDs are scoped to one host incarnation and crash-tail
   handling is reader/validation behavior. Describe cursor reading conceptually without naming a public Rust
   `read_after` API.
6. Run `make session-host-test` outside the sandbox.

## Task 3: Remove legacy append choices

**Files:**

- Modify: `session-host/src/journal.rs`
- Modify: `session-host/src/platform/unix.rs`
- Test: inline tests in `session-host/src/journal.rs`

1. Replace the production APIs with `append(event_type, payload)` and `append_durable(event_type, payload)`. Encode
   journal schema version `1` and record flags `0` inside the writer.
2. Keep deterministic event-ID injection only as a private `#[cfg(test)]` seam if unit tests require it. Rename it
   to make test-only scope explicit and remove schema/flags parameters from it.
3. Update every production and test caller. Preserve control-protocol schema/flags and signal payload flags.
4. First commit the behavior/API replacement with its positive and edge-case coverage. Then remove legacy-only
   rejection tests in a separate development commit, as required by `AGENTS.md`; the task branch will be squashed
   before integration.
5. Run `make session-host-test` outside the sandbox.

## Task 4: Delete the runtime reader and reduce the production scanner

**Files:**

- Modify: `session-host/src/journal.rs`
- Modify: `session-host/tests/support/journal.rs`
- Test: inline tests in `session-host/src/journal.rs`
- Test: `session-host/tests/unix_process_host.rs`

1. Delete the public `JournalEvent`, `RetentionGap`, `ReadResult`, `read`, and `read_after` API, including its
   snapshot/retry/result assembly path.
2. Replace the event-collecting production scan result with the smallest shared structural scan needed by
   creation-time validation, first-event discovery, and retention. It may report boundaries and first/last event
   IDs, but must not return decoded journal events or model a client cursor result.
3. Validate CBOR item boundaries, the fixed journal record shape, monotonic event IDs, and closed-segment integrity.
   Preserve the existing rule that only an incomplete final item in the active segment is a tolerable crash tail.
4. Make retention and first-event discovery consume this one scanner. Do not leave separate full-decode and
   metadata-only production scans.
5. Keep payload decoding, cursor filtering, gap presentation, and integration assertions exclusively in test
   support. Retain coverage for creation, append, rotation, compressed segments, validation, retention, corruption,
   event ordering, and active crash tails.
6. Run `make session-host-test` outside the sandbox.

## Task 5: Verify the single-path result

**Files:**

- Verify: `session-host/src/journal.rs`
- Verify: `session-host/src/platform/unix.rs`
- Verify: `session-host/tests/support/journal.rs`
- Verify: `session-host/protocol/fixtures/`

1. Search the repository and confirm there are no remaining production references to `JournalWriter::recover`,
   writer `journal_id`, public journal `read_after`, `ReadResult`, or schema/flags append arguments.
2. Confirm the only production writer construction is `JournalWriter::create(directory, config)` and retention,
   validation, and first-event discovery share the same minimal scanner.
3. Compare fixture hashes with the Task 1 baseline; every checked-in byte must be identical.
4. Run `git diff --check`.
5. Run `make session-host-test` outside the sandbox.
6. Create the logical implementation commit, then run `make test` outside the sandbox as required by `AGENTS.md`.
   If a fix is needed after that commit, use the same commit subject for the follow-up so orchestration can squash
   the task branch cleanly.

The review orchestrator owns final review, user approval, task-node deletion, squash, cherry-pick to `main`, the
post-integration `make test`, and branch/worktree cleanup.

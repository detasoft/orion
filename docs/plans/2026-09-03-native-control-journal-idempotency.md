# Native Control Journal Idempotency Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make native established-session controls idempotent across AgentD reconnects and restarts while
deleting journal segments only after the server durably acknowledges their complete event prefix.

**Architecture:** A live, bounded host ledger records every accepted command and result in the session journal
and suppresses duplicate effects. AgentD recovers from the server's committed prefix plus the host journal's
unacknowledged suffix. A non-journaled `ACK_JOURNAL` atomically persists one local retention watermark before
the existing maintenance worker may delete any covered closed segment.

**Tech Stack:** Rust 2024, binary local control framing, CBOR Sequence journal, atomic filesystem replacement,
Unix PTY/process host abstractions, Maven/Make Rust bootstrap.

---

## Scope rules

- Work only in the dedicated task worktree and follow @superpowers:test-driven-development for every behavior
  change.
- Follow `docs/plans/2026-09-03-native-control-journal-idempotency-design.md`.
- Run every test command outside the sandbox as required by `AGENTS.md`.
- Do not implement AgentD/server behavior, Java journal projection, `START_SESSION` sequencing, or ordered
  harness-event ingress.
- Keep `APPEND_EVENT` on its existing unsupported path.
- Do not recover or resume a failed host incarnation. AgentD recovery assumes the original host remains alive.
- Keep journal `eventId`, unsigned `operationSequence`, request correlation ID, and metadata timestamps distinct.
- Preserve exact server CBOR command bytes. Native delivery compares them but never decodes or re-encodes them.
- Keep the v1 fixtures frozen. Add v2 fixtures instead of rewriting existing bytes.
- `ACK_JOURNAL` is never a journal record. Its sidecar is local retention permission, not replication authority.

### Task 0: Verify the rebased baseline

**Files:**

- Inspect: `docs/plans/2026-09-03-native-control-journal-idempotency-design.md`
- Inspect: `session-host/src/journal.rs`
- Inspect: `session-host/src/platform/unix.rs`
- Inspect: `session-host/protocol/README.md`

**Step 1: Verify branch placement and cleanliness**

Run `git status --short` and `git log --oneline --decorate -6`.

Expected: no worktree changes after the plan commit, and the task commits are directly above current `main`.

**Step 2: Verify the current retention mismatch**

Inspect `JournalMaintenance`, `reconcile_journal`, and `enforce_retention`. Confirm compression and
physical-size deletion share one worker, deletion is not acknowledgement-gated, and no watermark is persisted.

**Step 3: Run the focused baseline**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all existing session-host tests pass before behavior changes.

### Task 1: Gate physical deletion on an acknowledged event watermark

**Files:**

- Modify: `session-host/src/journal.rs`
- Modify: `session-host/protocol/README.md`

**Step 1: Write failing retention-planning tests**

Extend the `journal.rs` unit tests so every closed segment size also has a discovered last `eventId`. Cover:

- no acknowledged watermark selects no deletion even above the physical limit;
- a watermark below a closed segment's last event selects no deletion;
- an acknowledged oldest prefix is selected only as far as needed to meet the size target;
- a target requiring an unacknowledged segment leaves the journal above target; and
- the active segment is never selected.

Use an explicit shape equivalent to:

```rust
struct SegmentSize {
    number: u64,
    last_event_id: u64,
    physical_bytes: u64,
    active: bool,
}

fn retention_deletions(
    segments: &[SegmentSize],
    journal_max_bytes: u64,
    acknowledged_event_id: Option<u64>,
) -> Result<Vec<u64>, JournalError>;
```

**Step 2: Write failing maintenance tests**

Change the physical-retention test to prove closed raw segments are compressed but retained before an ACK.
Then call the wished-for API and assert only fully covered closed segments disappear:

```rust
writer.apply_retention_through(acknowledged_event_id)?;
```

Adapt deletion-failure/retry coverage so a watermark is supplied before deletion is expected. Add a
repeated-watermark case that retries an earlier failed deletion without regressing state.

**Step 3: Run the tests to verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: FAIL because retention still deletes without an ACK and `apply_retention_through` does not exist.

**Step 4: Extend the existing single maintenance worker**

Keep the worker as the sole compressor and segment deleter. Add an apply command carrying the active segment,
monotonic watermark, and a one-shot result sender. The worker retains the greatest received watermark in
memory. Ordinary rotate/reconcile commands compress closed segments and apply size retention using that
watermark; with no watermark they perform compression only.

Discover each closed segment's final event ID from its complete CBOR sequence. Do not persist an index or a
second acknowledgement value in `journal.rs`. `JournalWriter::apply_retention_through` sends the command,
waits for that reconciliation attempt, and is idempotent for repeated/lower values. It consumes permission that
the control layer has already persisted; it never writes the sidecar itself.

Delete in ascending segment order and sync the containing directory after each removal. Keep retry behavior for
compression and deletion failures, and never delete the active segment or a segment whose final event exceeds
the watermark.

**Step 5: Run the tests to verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all compression, gated-retention, failure-retry, reader-race, and existing journal tests pass.

**Step 6: Commit the retention gate**

Commit the two listed files with subject `Gate session journal deletion on acknowledgement`, then run
`make test`.

### Task 2: Freeze the v2 control and command-event contracts

**Files:**

- Modify: `session-host/src/protocol.rs`
- Modify: `session-host/src/host.rs`
- Modify: `session-host/protocol/README.md`
- Modify: `session-host/src/bin/generate_protocol_fixtures.rs`
- Create: `session-host/protocol/fixtures/control-idempotency-v2.bin`
- Create: `session-host/protocol/fixtures/command-events-v1.hex`

**Step 1: Write failing v2 payload tests**

Add tests for `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` schema 2 payloads with this wrapper:

```text
u64 operationSequence, little endian and nonzero
u16 commandIdLength, little endian and 1 through 128
commandIdLength bytes matching [A-Za-z0-9][A-Za-z0-9._:-]{0,127}
u32 commandEnvelopeLength, little endian and nonzero
commandEnvelopeLength exact opaque server CBOR item bytes
remaining bytes: the existing command-specific effect payload
```

Assert parsing owns the CommandId, envelope, and effect bytes by mutating source buffers after construction.
Cover zero sequence, unsafe or oversized CommandId, empty/truncated/oversized envelope, and command-specific
effect-length validation. Include unsigned values above `i64::MAX`.

**Step 2: Write failing ACK and journal-event tests**

Allocate `ACK_JOURNAL = 0x0007`; schema 1 contains exactly one nonzero little-endian `u64 eventId`.

Allocate system event types `COMMAND_ACCEPTED = 0x0001` and `COMMAND_RESULT = 0x0002`:

```text
COMMAND_ACCEPTED: [operationSequence, byte-string exactCommandEnvelope]
COMMAND_RESULT:   [operationSequence, byte-string commandId, outcome, detail]
```

Freeze outcomes `1 SUCCEEDED`, `2 FAILED`, `3 REJECTED`, and `4 AMBIGUOUS`. The live host does not
generate `AMBIGUOUS`, but the shared record contract reserves it. Success detail is empty and other UTF-8
detail is bounded to 4096 bytes.

Assert the existing `control-v1.bin` and journal v1 fixtures remain byte-identical. Add failing identity checks
for the two new fixtures.

**Step 3: Run the tests to verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: failure because the v2 wrappers, ACK type, command events, and fixtures do not exist.

**Step 4: Implement exact encoders and owned decoders**

Add small typed owned values for the common v2 wrapper, acknowledgement watermark, and command outcome. Reuse
the current command-specific validation for dimensions, signals, and termination. Check all additions against
the 16 MiB control payload limit before allocating or slicing. Keep `STATUS` and `ACK_JOURNAL` on schema 1.

Add exact CBOR event encoders without parsing the opaque command envelope. Keep CommandId encoded as bytes in
the system records so native code copies it without UTF-8 normalization after initial validation.

**Step 5: Generate and inspect additive fixtures**

Extend the generator with one v2 request per established control, one ACK request, both system records, an
envelope containing an appended unknown CBOR field, and unsigned values above `i64::MAX`.

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all protocol tests and old/new fixture identity checks pass.

**Step 6: Commit the native contracts**

Commit the six listed files with subject `Define native idempotent control contracts`, then run `make test`.

### Task 3: Validate and force durability for command records

**Files:**

- Modify: `session-host/src/journal.rs`

**Step 1: Write failing known-event tests**

Add read/write cases for `COMMAND_ACCEPTED` and `COMMAND_RESULT`, including maximum unsigned sequences, exact
envelope bytes with unknown CBOR fields, every outcome, result CommandId bytes, and valid diagnostic detail.
Reject malformed arrays, missing fields, invalid result CommandIds, unknown outcomes, invalid UTF-8 detail, and
oversized detail.

**Step 2: Write a failing explicit-sync test**

Add a private file seam or the smallest observable writer test proving command records request `sync_data`
even when `JournalConfig.durability` is `Buffered`. Do not make PTY output use per-record sync.

**Step 3: Run the tests to verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: failure because the journal treats the new system types as opaque and exposes no forced-sync append.

**Step 4: Implement typed validation and durable append**

Extend `known_event_type` and `decode_known_payload` while preserving complete encoded payload and record
bytes. Add a narrow API equivalent to:

```rust
pub fn append_durable(
    &mut self,
    event_type: u16,
    payload: &[u8],
) -> Result<u64, JournalError>;
```

It appends one complete CBOR item and calls `sync_data` before returning regardless of buffered PTY policy.
Do not change ordinary `append` durability.

**Step 5: Run the tests to verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all known/opaque event, crash-tail, and durability tests pass.

**Step 6: Commit journal command durability**

Commit `session-host/src/journal.rs` with subject `Validate durable command journal records`, then run
`make test`.

### Task 4: Build the bounded live-operation ledger

**Files:**

- Create: `session-host/src/control_journal.rs`
- Modify: `session-host/src/lib.rs`
- Modify: `session-host/src/cli.rs`
- Modify: `session-host/src/main.rs`

**Step 1: Write failing ledger-admission tests**

Define an owned identity containing sequence, CommandId, exact envelope, control type, and effect bytes. Test
admission results equivalent to:

```rust
enum Admission {
    New,
    Pending,
    Completed { result_event_id: u64 },
    Conflict,
    Stale,
    Full,
}
```

Cover gaps, maximum unsigned sequence, identical pending and completed retries, conflicts in every identity
field, an unexplained sequence below the high-water mark, capacity at/beyond the limit, and a completed retry
while full. No test may reconstruct this ledger from an old journal.

**Step 2: Write failing acknowledgement-eviction tests**

Complete operations with distinct result IDs. Acknowledge a prefix and assert covered completed details are
evicted, pending/later details remain, capacity becomes available, and the accepted sequence high-water mark
does not decrease. Repeat and lower the watermark to prove idempotence.

**Step 3: Write failing CLI tests**

Add `--max-unacknowledged-operations`, default `4096`, to `SessionOptions`. Cover default, custom,
duplicate, malformed, and zero values.

**Step 4: Run the tests to verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: compilation failure because the ledger and CLI option do not exist.

**Step 5: Implement only the live ledger**

Use an ordinary bounded map keyed by unsigned sequence. Keep pending/completed state and the accepted sequence
high-water mark in memory for the host lifetime. Admission occurs before intent or effect. Mark an operation
pending only after `COMMAND_ACCEPTED` is durable, and completed only after `COMMAND_RESULT` is durable. If
intent append fails, cancel its reservation without advancing durable command state.

Do not add checkpoint loading, journal scanning, unmatched-intent recovery, or synthetic `AMBIGUOUS` results.
The server prefix and journal suffix are AgentD recovery inputs; this ledger only protects the running host.

**Step 6: Thread the capacity option into host construction**

Parse a positive decimal capacity, preserve the default, and pass it from `SessionOptions` through `main.rs`.

**Step 7: Run the tests to verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: ledger and CLI tests pass with all existing module tests.

**Step 8: Commit the live ledger**

Commit the four listed files with subject `Track live native control operations`, then run `make test`.

### Task 5: Persist the server acknowledgement beside the journal

**Files:**

- Create: `session-host/src/journal_acknowledgement.rs`
- Modify: `session-host/src/lib.rs`

**Step 1: Write failing state and validation tests**

Specify a versioned UTF-8 JSON file named `control-retention-state` containing only:

```json
{"stateVersion":1,"acknowledgedEventId":42}
```

Treat absence as no acknowledgement. Test creation, monotonic advancement, repeated/lower values, invalid JSON,
unknown versions, zero values, and ignored unknown object fields. Validate a received watermark separately
against the current logical journal tail before writing it.

**Step 2: Write failing crash-boundary tests**

Introduce a private filesystem seam for temporary-file write/sync, rename, and directory sync. Inject failures:

- before rename: reopening observes only the old watermark;
- after rename but before directory sync: advancement is not reported as accepted;
- after successful directory sync: reopening observes the new watermark; and
- an abandoned temporary file never overrides the published checkpoint.

Assert retention is never invoked before the checkpoint is fully durable.

**Step 3: Run the tests to verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: compilation failure because `JournalAcknowledgement` does not exist.

**Step 4: Implement atomic durable replacement**

Write complete next state to a fixed temporary path, call `sync_data`, atomically rename it over
`control-retention-state`, and sync the directory before returning. Remove or replace an abandoned temporary
file on the next advancement. This is durable even when ordinary journal writes are buffered.

This component owns the only persisted acknowledgement. It does not persist an operation high-water mark,
write metadata, append an ACK event, or become an AgentD cursor.

**Step 5: Run the tests to verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all state, monotonicity, and crash-boundary tests pass.

**Step 6: Commit the durable sidecar**

Commit the two listed files with subject `Persist durable journal acknowledgement`, then run `make test`.

### Task 6: Route all established controls through the ledger

**Files:**

- Modify: `session-host/src/platform/unix.rs`
- Modify: `session-host/tests/unix_process_host.rs`

**Step 1: Write failing end-to-end happy-path tests**

Replace operation-control requests with schema 2 wrappers. Add table-driven coverage for `INPUT`, `RESIZE`,
`SIGNAL`, and `TERMINATE`. For each assert journal order:

```text
COMMAND_ACCEPTED, existing effect event, COMMAND_RESULT
```

Assert `ACCEPTED` contains the result record's `eventId`, not the effect record's ID. Keep `STATUS` on
schema 1 and assert it remains responsive while a control effect is blocked.

**Step 2: Write failing reconnect and conflict tests**

Close the first AgentD-like connection and retry from a second with a changed request ID. Cover identical
pending and completed retries, gaps, conflicting CommandId/envelope/effect bytes, a stale unknown sequence, and
source-buffer mutation. Assert one effect and one accepted/result pair per new operation.

**Step 3: Write failing ACK and retention tests**

Use small journal limits to rotate several segments. Prove:

- closed segments compress but remain before ACK;
- zero and future ACKs return `ERROR_INVALID_REQUEST`;
- a valid ACK durably writes `control-retention-state` before deletion is requested;
- repeated/lower ACKs return the current watermark and retry maintenance;
- only fully covered, size-selected closed segments are deleted; and
- ACK eviction frees ledger capacity without lowering its sequence high-water mark.

Assert no `ACK_JOURNAL` event exists and metadata has no acknowledgement field.

**Step 4: Run the tests to verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: v2 controls and ACK are unsupported and old handlers bypass the common ledger.

**Step 5: Implement the shared v2 control flow**

Replace `accepted_inputs` and `input_order` with the ledger and one established-control ordering lock. Parse
and validate before admission. Under the order lock, reserve a new identity and durably append
`COMMAND_ACCEPTED`; then perform the existing effect without holding the shared-state mutex where that would
block `STATUS` or ACK handling. Preserve existing effect-event ordering before the PTY/ioctl/signal action.

After admission, convert actual success or failure into a durable `COMMAND_RESULT`; only pre-admission
validation/lifecycle/I/O failures remain transient `ERROR`. Do not reply `ACCEPTED` until the result is
synced. Matching completed retries return the original result ID. Matching pending retries return a transient
in-progress response without another effect.

Reject schema 1 for the four operation controls after migration while keeping its fixture bytes frozen. Keep
`APPEND_EVENT` unsupported and independently serve schema-1 `STATUS`.

**Step 6: Implement ACK handling in durability order**

For schema-1 `ACK_JOURNAL`, validate against `journal.latest_event_id()`. Persist a greater watermark and
wait for directory sync before replying. Then evict covered completed ledger entries and ask the journal worker
to apply retention through the durable watermark.

Deletion or compression failure does not roll back the server acknowledgement. Record it for retry and still
return the durable watermark. Repeated/lower ACKs re-signal maintenance so a prior failure can finish.

**Step 7: Run the tests to verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all controls, reconnect/idempotency, ACK durability, retention, and existing host tests pass.

**Step 8: Commit the host integration**

Commit the two listed files with subject `Make native controls durably idempotent`, then run `make test`.

### Task 7: Complete documentation, compatibility, and verification

**Files:**

- Modify: `session-host/protocol/README.md`
- Modify: `session-host/README.md`
- Inspect: `agent-protocol/protocol/fixtures/session-events-v1.hex`
- Inspect: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolFixtureTest.java`

**Step 1: Document recovery and authority boundaries**

Document AgentD recovery from the server prefix plus live-host suffix. State that the sidecar is only durable
deletion permission, the host does not resume a failed incarnation, ACK follows a durable server response, and
physical size may remain above target while acknowledgement lags.

Document all v2 layouts, result outcomes, exact-envelope rule, capacity option, and failure semantics. Preserve
v1 fixture descriptions and bytes.

**Step 2: Verify fixture ownership**

Inspect the generated command-event fixture and Java fixture test. Confirm this task supplies canonical native
bytes but does not add Java typed projection; that remains in
`docs/plans/current-work/agentd/command-orchestration/TASK.md`.

**Step 3: Run focused verification**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: PASS.

**Step 4: Run routine development verification**

Run `mvn verify -Pdev -T 4` and `git diff --check`.

Expected: Maven reports `BUILD SUCCESS` and the diff check prints nothing.

**Step 5: Commit documentation**

Commit the documentation files with subject `Document native control recovery semantics`, then run
`make test`.

### Task 8: Prepare the mandatory review gate

**Files:**

- Inspect: every file changed from the recorded base through branch HEAD
- Inspect: `docs/reviews/RULES.md`

**Step 1: Audit requirements and local rules**

Check every requirement, changed class-level `@AiRule`, source-line lengths, fixture stability, and blocking
review criteria. Confirm there is no AgentD/server implementation, host-incarnation recovery, journaled ACK,
or second persisted acknowledgement value.

**Step 2: Run fresh completion verification**

Run:

```text
make run-test MODULE=session-host TEST='*'
mvn verify -Pdev -T 4
git diff --check <base-sha>...HEAD
make test
```

Expected: every command exits zero with no test failure.

**Step 3: Request review and stop**

Use @superpowers:requesting-code-review with the real base and head SHAs. Report commits, changed files,
verification evidence, and risks. Do not squash, delete the task node, cherry-pick to main, or remove the
worktree/branch before explicit follow-up authorization.

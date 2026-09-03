# Native Session-Host Start-Outcome Contract Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Guarantee one durable native journal outcome for every session start that reaches journal creation.

**Architecture:** Extend the shared journal format with a bounded `SESSION_START_FAILED` record and carry the
server CommandId through `SessionSpec` and the native CLI. Keep the journal writer pending during native
initialization, then resolve it exactly once with durable failure or success before constructing live host state;
preserve any readable failed journal during AgentD handoff cleanup.

**Tech Stack:** Rust 2024, CBOR Sequence journal fixtures, Unix PTY setup pipe, Java 21 records, JUnit 5,
AssertJ, Maven.

---

### Task 1: Freeze the start-outcome journal contract

**Files:**
- Modify: `session-host/src/protocol.rs`
- Modify: `session-host/src/journal.rs`
- Modify: `session-host/src/bin/generate_protocol_fixtures.rs`
- Create: `session-host/protocol/fixtures/start-outcomes-v1.hex`
- Create: `agent-protocol/protocol/fixtures/start-outcomes-v1.hex`
- Modify: `session-host/protocol/README.md`
- Modify: `agent-protocol/protocol/README.md`

**Step 1: Write failing protocol tests**

Add Rust unit tests that require:

```rust
assert_eq!(event_type::SESSION_START_FAILED, 0x0203);
assert_eq!(
    encode_session_start_failed(2, "command.start", "exec failed", 0).unwrap(),
    expected_cbor,
);
```

Cover a valid record, invalid CommandIds, diagnostics over 1 MiB, and the generated start-outcome fixture. Add
journal tests requiring the reader to recognize and validate the known failure payload while preserving its
encoded payload and record.

**Step 2: Run the Rust tests to verify they fail**

Run: `make session-host-test`

Expected: FAIL because the event allocation, encoder, reader handling, and fixture generator do not exist.

**Step 3: Implement the protocol encoder and diagnostic bound**

Add:

```rust
pub const MAX_START_DIAGNOSTIC_BYTES: usize = 1024 * 1024;
pub const START_DIAGNOSTIC_PREFIX_BYTES: usize = 64 * 1024;
pub const START_DIAGNOSTIC_SUFFIX_BYTES: usize = 960 * 1024;

pub fn encode_session_start_failed(
    event_id: u64,
    command_id: &str,
    diagnostic: &str,
    omitted_byte_count: u64,
) -> Result<Vec<u8>, EncodeError>;

pub fn bound_start_diagnostic(diagnostic: &str) -> (String, u64);
```

Encode `[eventId, 0x0203, [commandId, diagnostic, omittedByteCount]]`. Reuse one CommandId validator for
operation records and the native CLI. Truncate only on UTF-8 character boundaries and calculate omission from
the original and retained UTF-8 byte lengths.

Add a compact internal payload builder/decoder for `JournalWriter::append` so `journal::encode_event` and
`decode_known_payload` validate the same fields without changing generic journal framing.

**Step 4: Generate and mirror the golden fixture**

Extend `protocol_fixture` with one `PROCESS_STARTED` record and one `SESSION_START_FAILED` record using fixed
event IDs and values. Extend the generator, run:

```bash
make session-host-fixtures
cp session-host/protocol/fixtures/start-outcomes-v1.hex \
  agent-protocol/protocol/fixtures/start-outcomes-v1.hex
```

Expected: the two checked-in files are byte-identical and the fixture unit test passes.

**Step 5: Document the contract**

Add `SESSION_START_FAILED`, its payload fields and bounds, durable exactly-one start semantics, and the new
fixture to both protocol READMEs. Do not add Java typed decoding in this task.

**Step 6: Run the Rust tests**

Run: `make session-host-test`

Expected: PASS.

**Step 7: Commit the protocol slice**

```bash
git add session-host/src/protocol.rs session-host/src/journal.rs \
  session-host/src/bin/generate_protocol_fixtures.rs session-host/protocol \
  agent-protocol/protocol
git commit -m "Define native session start outcome records"
```

### Task 2: Carry the start CommandId to the native host

**Files:**
- Modify: `session-host/src/cli.rs`
- Modify: `session-host/src/main.rs`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/SessionSpec.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/NativeRuntime.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/SessionContractsTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`
- Test: `session-host/src/cli.rs`

**Step 1: Write failing CLI and AgentD mapping tests**

Require `--start-command-id` in the complete/default CLI cases and assert missing, duplicate, empty, oversized,
and unsafe values fail before session execution. Add `CommandId startCommandId` to expected `SessionSpec` values
and assert `NativeRuntime` launches:

```text
session-host --session-id session-1 --start-command-id command.start ...
```

**Step 2: Run focused tests to verify they fail**

Run: `make session-host-test`

Expected: FAIL in CLI tests because the option is unknown or not required.

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.runtime.SessionContractsTest,pro.deta.orion.agentd.runtime.NativeRuntimeTest'`

Expected: FAIL because `SessionSpec` has no CommandId and the native command omits it.

**Step 3: Implement the required identity seam**

Add `start_command_id: String` to `SessionOptions` and parse it with the shared native CommandId validation rule.
Add `CommandId startCommandId` to `SessionSpec`, require it non-null, update all construction sites, and pass its
value unchanged from `NativeRuntime.command` to `--start-command-id`. Update CLI usage text.

**Step 4: Run focused tests**

Run: `make session-host-test`

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.runtime.SessionContractsTest,pro.deta.orion.agentd.runtime.NativeRuntimeTest'`

Expected: PASS.

**Step 5: Commit the identity seam**

```bash
git add session-host/src/cli.rs session-host/src/main.rs agentd/src
git commit -m "Pass start command identity to session host"
```

### Task 3: Resolve every native post-journal start attempt

**Files:**
- Modify: `session-host/src/platform/unix.rs`
- Modify: `session-host/src/host.rs`
- Modify: `session-host/tests/unix_process_host.rs`

**Step 1: Replace the legacy failed-exec assertion with failing outcome tests**

Change `failed_exec_never_crosses_the_successful_launch_boundary` to require exactly one
`SESSION_START_FAILED`, no `PROCESS_STARTED`, the supplied CommandId, a useful diagnostic, and zero omitted
bytes. Add a missing-working-directory case and a post-journal initialization failure case. Extend the normal
launch assertion to require exactly one `PROCESS_STARTED` and no `SESSION_START_FAILED`.

Do not add a test whose only purpose is proving legacy behavior absent; each assertion must establish the new
authoritative outcome contract.

**Step 2: Run the Unix host test to verify it fails**

Run: `make session-host-test`

Expected: FAIL because launch errors still leave an empty journal and success still uses buffered append.

**Step 3: Implement explicit pending-start resolution**

Keep validation that precedes journal creation outside the outcome boundary. After `JournalWriter::create`, use
a small `PendingStartOutcome` coordinator holding the writer and start CommandId. Initialization borrows this
state until it returns either confirmed child resources or `HostError`.

On initialization error:

```rust
let (diagnostic, omitted) = protocol::bound_start_diagnostic(&error.to_string());
pending.fail(&diagnostic, omitted)?; // append_durable SESSION_START_FAILED
return Err(error);
```

After the setup pipe closes on successful `exec`, append `PROCESS_STARTED` with `append_durable`, mark the
pending state resolved, and transfer its writer into `SharedState`. Later metadata, reader, control, wait, or
exit errors never produce a second start outcome.

When failure-outcome persistence also fails, return a dedicated `HostError` variant containing both errors so
the original cause is not lost. Do not attempt to use `Drop` for fallible journal writes.

**Step 4: Run and format the Rust code**

Run: `make session-host-test`

Run the pinned formatter through the prepared toolchain:

```bash
RUSTUP_HOME=.orion-cache/rust-toolchains/1.97.0-aarch64-apple-darwin/rustup \
CARGO_HOME=.orion-cache/rust-toolchains/1.97.0-aarch64-apple-darwin/cargo \
.orion-cache/rust-toolchains/1.97.0-aarch64-apple-darwin/cargo/bin/cargo \
fmt --manifest-path session-host/Cargo.toml -- --check
```

Expected: tests and format check PASS. If the host triple differs, use the cache path selected by
`make session-host-prepare` rather than the literal Darwin path.

**Step 5: Commit native outcome resolution**

```bash
git add session-host/src/platform/unix.rs session-host/src/host.rs \
  session-host/tests/unix_process_host.rs
git commit -m "Persist native session start outcomes"
```

### Task 4: Preserve failed journals during AgentD handoff

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/runtime/NativeRuntime.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java`

**Step 1: Write failing cleanup tests**

Keep the existing missing-journal early-exit test. Add an early host exit with `JournalObservation.READABLE` and
assert the session directory remains. Update timeout coverage so a readable journal is also preserved after the
tentative process is stopped, while a missing journal remains removable.

**Step 2: Run the focused test to verify it fails**

Run: `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.runtime.NativeRuntimeTest'`

Expected: FAIL because `cleanup` deletes every confirmed-stopped launch directory.

**Step 3: Preserve the journal authority boundary**

After confirming the tentative process stopped, probe the journal. Delete the directory only for `MISSING`;
preserve `READABLE` and `CORRUPT` state for journal reading or diagnosis. If the probe itself fails, preserve the
directory and include that failure in the transient cleanup diagnostic rather than risking journal deletion.

**Step 4: Run focused AgentD tests**

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.runtime.SessionContractsTest,pro.deta.orion.agentd.runtime.NativeRuntimeTest'`

Expected: PASS.

**Step 5: Commit AgentD preservation**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/runtime/NativeRuntime.java \
  agentd/src/test/java/pro/deta/orion/agentd/runtime/NativeRuntimeTest.java
git commit -m "Preserve journaled native start failures"
```

### Task 5: Verify, review, and integrate the completed leaf

**Files:**
- Modify: `docs/plans/current-work/native-session-host/TASK.md`
- Delete: `docs/plans/current-work/native-session-host/start-outcome-contract/TASK.md`

**Step 1: Run focused and routine development verification outside the sandbox**

Run: `make session-host-test`

Run:
`make run-test MODULE=agentd TEST='pro.deta.orion.agentd.runtime.SessionContractsTest,pro.deta.orion.agentd.runtime.NativeRuntimeTest'`

Run: `mvn verify -Pdev -T 4`

Expected: PASS.

**Step 2: Request code review and apply only verified blocking fixes**

Use `superpowers:requesting-code-review`. Read `docs/reviews/RULES.md`, review the complete task diff against
the approved design, and apply any blocking correctness or contract fixes. Repeat affected focused tests.

**Step 3: Squash the task branch**

Remove the completed leaf directory and its parent link. Squash every task-branch commit into one commit with:

```text
Guarantee durable native session start outcomes [task: native-session-host/start-outcome-contract]
```

**Step 4: Transfer to main and run the required post-commit test**

Cherry-pick the squashed commit onto `main`, then run `make test` outside the sandbox. If a task-caused failure
is fixed, use the exact same commit subject for the follow-up commit so it can be squashed later.

Expected: PASS.

**Step 5: Clean up the dedicated worktree**

Confirm both worktrees are clean, remove `.worktrees/native-start-outcome-a94c`, delete branch
`codex/native-start-outcome-a94c`, and verify `git worktree list` no longer contains it. Do not report the task
complete before these conditions hold.

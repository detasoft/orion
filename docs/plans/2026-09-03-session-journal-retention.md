# Session Journal Segmentation, Compression, and Retention Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bound each native session journal with configurable segment rotation,
background Zstandard compression, crash-safe replacement, and `DROP_OLDEST`
retention without delaying terminal writes.

**Architecture:** `JournalWriter` remains the only active-segment writer and
rotates before an append that would cross the configured target. One owned
maintenance thread reconciles closed segments, publishes compressed copies,
and deletes the oldest closed prefix. Readers tolerate replacement and
retention races while deriving all ranges from segment contents.

**Tech Stack:** Rust 2024, `std::fs`, `std::sync::mpsc`, `zstd` 0.13, existing
CBOR Sequence parser, Maven/Make Rust bootstrap.

---

Run every test command in this plan outside the sandbox as required by
`AGENTS.md`. Use @superpowers:test-driven-development for every behavior change
and @superpowers:systematic-debugging for any unexpected failure.

### Task 1: Expose Journal Limits Through the CLI

**Files:**

- Modify: `session-host/src/cli.rs:5-185`
- Modify: `session-host/src/main.rs:5-30`

**Step 1: Write the failing default and explicit-value tests**

Extend `SessionOptions` expectations in `cli.rs` and add tests with the desired
public contract:

```rust
#[test]
fn supplies_default_journal_limits() {
    let Command::Run(options) = parse(strings(&[
        "session-host", "--session-id", "session-1",
        "--session-dir", "/sessions/session-1", "--cwd", "/work",
        "--", "sh",
    ])).unwrap() else {
        panic!("expected run command");
    };

    assert_eq!(options.journal_segment_bytes, 64 * 1024 * 1024);
    assert_eq!(options.journal_max_bytes, 1024 * 1024 * 1024);
}

#[test]
fn parses_explicit_journal_limits() {
    let Command::Run(options) = parse(strings(&[
        "session-host", "--session-id", "session-1",
        "--session-dir", "/sessions/session-1", "--cwd", "/work",
        "--journal-segment-bytes", "4096",
        "--journal-max-bytes", "16384",
        "--", "sh",
    ])).unwrap() else {
        panic!("expected run command");
    };

    assert_eq!(options.journal_segment_bytes, 4096);
    assert_eq!(options.journal_max_bytes, 16384);
}
```

**Step 2: Run the module tests and verify RED**

Run:

```bash
make run-test MODULE=session-host TEST='*'
```

Expected: Rust compilation fails because `SessionOptions` has no journal limit
fields.

**Step 3: Add constants, fields, parsing, and usage text**

Add these constants and fields:

```rust
pub const DEFAULT_JOURNAL_SEGMENT_BYTES: u64 = 64 * 1024 * 1024;
pub const DEFAULT_JOURNAL_MAX_BYTES: u64 = 1024 * 1024 * 1024;

pub struct SessionOptions {
    // existing fields
    pub journal_segment_bytes: u64,
    pub journal_max_bytes: u64,
}
```

Parse both option values as decimal `u64`. Reject zero, duplicates, and a final
configuration where `journal_max_bytes < journal_segment_bytes`. Add the two
options and defaults to `USAGE` in `main.rs`.

**Step 4: Add invalid-value tests**

Cover malformed, zero, duplicate, and inverted limits:

```rust
#[test]
fn rejects_invalid_journal_limits() {
    for arguments in [
        vec!["--journal-segment-bytes", "0"],
        vec!["--journal-max-bytes", "nope"],
        vec!["--journal-segment-bytes", "8192", "--journal-max-bytes", "4096"],
    ] {
        let mut command = vec![
            "session-host", "--session-id", "session-1",
            "--session-dir", "/sessions/session-1", "--cwd", "/work",
        ];
        command.extend(arguments);
        command.extend(["--", "sh"]);
        assert!(parse(strings(&command)).is_err());
    }
}
```

Also add one explicit duplicate-option assertion alongside the existing
duplicate coverage.

**Step 5: Run the module tests and verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all session-host Rust tests pass.

**Step 6: Commit**

```bash
git add session-host/src/cli.rs session-host/src/main.rs
git commit -m "Expose session journal size limits"
```

### Task 2: Rotate Automatically at CBOR Item Boundaries

**Files:**

- Modify: `session-host/src/journal.rs:18-265`
- Test: `session-host/src/journal.rs:1219-1281`

**Step 1: Write failing automatic-rotation tests**

Add a test configuration helper and tests showing that rotation happens before
the item that would cross the target:

```rust
fn journal_config(segment_max_bytes: u64, journal_max_bytes: u64) -> JournalConfig {
    JournalConfig {
        durability: Durability::Buffered,
        segment_max_bytes,
        journal_max_bytes,
    }
}

#[test]
fn rotates_before_an_item_would_cross_the_segment_limit() {
    let directory = temporary_directory("automatic-rotation");
    let mut writer = JournalWriter::create(
        &directory,
        [7; 16],
        journal_config(12, 1024),
    ).unwrap();

    writer.append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, b"one").unwrap();
    writer.append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"two").unwrap();
    writer.flush().unwrap();

    assert!(directory.join("00000001.cbor").is_file());
    assert!(directory.join("00000002.cbor").is_file());
    assert_eq!(read_after(&directory, 0).unwrap().events.len(), 2);
}

#[test]
fn keeps_one_oversized_item_indivisible() {
    let directory = temporary_directory("oversized-item");
    let mut writer = JournalWriter::create(
        &directory,
        [7; 16],
        journal_config(1, 1024),
    ).unwrap();

    writer.append_at(1, protocol::event_type::PTY_OUTPUT, 1, 0, b"whole").unwrap();
    writer.append_at(2, protocol::event_type::PTY_OUTPUT, 1, 0, b"next").unwrap();
    writer.flush().unwrap();

    assert_eq!(read_after(&directory, 0).unwrap().events[0].payload, b"whole");
}
```

Use exact encoded lengths when finalizing the first assertion so the test
proves the boundary rather than merely observing multiple files.

**Step 2: Run the module tests and verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: compilation fails because `JournalConfig` has no size fields, or the
rotation assertion fails before automatic rotation exists.

**Step 3: Extend and validate `JournalConfig`**

Add the approved defaults and reject invalid library callers in both `create`
and `recover`:

```rust
#[derive(Clone, Debug)]
pub struct JournalConfig {
    pub durability: Durability,
    pub segment_max_bytes: u64,
    pub journal_max_bytes: u64,
}

fn validate_config(config: &JournalConfig) -> Result<(), JournalError> {
    if config.segment_max_bytes == 0
        || config.journal_max_bytes < config.segment_max_bytes
    {
        return Err(JournalError::Configuration(
            "journal limits must be positive and max must cover one segment".to_owned(),
        ));
    }
    Ok(())
}
```

Add `JournalError::Configuration(String)` and its display branch.

**Step 4: Track active length and rotate before writing**

Store `active_length: u64` in `JournalWriter`. Initialize it to zero on create
and to the recovered active boundary on recover. In `append_at`, encode first,
then use checked arithmetic:

```rust
let record_length = u64::try_from(record.len())
    .map_err(|_| JournalError::Format("journal record length exceeds u64".to_owned()))?;
if self.active_length != 0
    && self.active_length.saturating_add(record_length) > self.config.segment_max_bytes
{
    self.rotate()?;
}
self.file.write_all(&record)?;
self.active_length = self.active_length
    .checked_add(record_length)
    .ok_or_else(|| JournalError::Format("active segment length overflow".to_owned()))?;
```

Reset the length only after the next segment is successfully created. Add
`active_segment_number()` for platform metadata integration later.

**Step 5: Run the module tests and verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all session-host tests pass, including existing golden-byte and
partial-tail tests.

**Step 6: Commit**

```bash
git add session-host/src/journal.rs
git commit -m "Rotate session journal segments automatically"
```

### Task 3: Compress Closed Segments in the Background

**Files:**

- Modify: `session-host/src/journal.rs:1-265`
- Test: `session-host/src/journal.rs:1515-1571`

**Step 1: Write failing compression tests**

Add tests that create at least two closed segments, wait for deterministic
maintenance completion, and assert:

```rust
writer.finish_maintenance().unwrap();
assert!(directory.join("00000001.cbor.zst").is_file());
assert!(!directory.join("00000001.cbor").exists());
assert!(directory.join("00000003.cbor").is_file());

let decoded = zstd::stream::decode_all(
    File::open(directory.join("00000001.cbor.zst")).unwrap(),
).unwrap();
assert_eq!(decoded, first_segment_bytes);
```

Use both repeated and deterministic pseudo-random payloads so compressible and
incompressible data preserve the exact logical CBOR Sequence.

**Step 2: Run the module tests and verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: compilation fails because `finish_maintenance` does not exist and
closed segments remain raw.

**Step 3: Add the owned maintenance thread**

Introduce private types with one small message per rotation:

```rust
enum MaintenanceCommand {
    Reconcile,
    Finish,
}

struct JournalMaintenance {
    sender: std::sync::mpsc::Sender<MaintenanceCommand>,
    thread: Option<std::thread::JoinHandle<Result<(), JournalError>>>,
}
```

Start the worker from `JournalWriter::create` and `recover`. After a successful
rotation, send `Reconcile`; never send record bytes through the channel. Make
`finish_maintenance` send `Finish`, join once, and return the worker's final
result. `Drop` must request shutdown and join while ignoring only errors that
can no longer be returned to the caller.

**Step 4: Implement crash-safe compression publication**

For each raw closed segment in number order:

```rust
let temporary = compressed_temporary_path(directory, number);
let published = compressed_segment_path(directory, number);
let input = File::open(&raw)?;
let output = File::create(&temporary)?;
let mut encoder = zstd::stream::write::Encoder::new(output, 3)?;
io::copy(&mut io::BufReader::new(input), &mut encoder)?;
let output = encoder.finish()?;
sync_file_if_required(&output, durability)?;
fs::rename(&temporary, &published)?;
sync_directory_if_required(directory, durability)?;
fs::remove_file(&raw)?;
sync_directory_if_required(directory, durability)?;
```

Only files whose number is below the current active number are eligible. Keep
the latest `.cbor` uncompressed.

**Step 5: Run the module tests and verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: compression tests and all existing reader tests pass.

**Step 6: Commit**

```bash
git add session-host/src/journal.rs
git commit -m "Compress closed session journal segments"
```

### Task 4: Recover Interrupted Compression Safely

**Files:**

- Modify: `session-host/src/journal.rs:367-512`
- Test: `session-host/src/journal.rs:1515-1580`

**Step 1: Write failing crash-window tests**

Seed and recover these directory states:

- raw `.cbor` plus `.cbor.zst.tmp`;
- identical raw `.cbor` plus published `.cbor.zst`;
- valid raw `.cbor` plus corrupt or logically different published `.cbor.zst`;
- closed raw segments followed by a valid active `.cbor`.

Assert that `read_after` always returns each event exactly once and that
`recover(...).finish_maintenance()` leaves one valid representation per closed
segment.

**Step 2: Run the module tests and verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: the current duplicate-name format error or leftover temporary files
fail the new assertions.

**Step 3: Reconcile transition artifacts**

Teach discovery to group files by segment number. When raw and compressed
published copies coexist, expose the raw copy to readers during the transition.
During maintenance:

1. remove abandoned `.cbor.zst.tmp` while raw exists;
2. decompress a published copy when raw also exists;
3. remove raw if bytes match;
4. otherwise remove the compressed copy and rebuild it from raw.

Do not accept a temporary file as a journal segment. Keep the existing error
for a compressed-only newest segment during writer recovery.

**Step 4: Run the module tests and verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all crash-window, duplicate-view, compressed-reader, and recovery
tests pass.

**Step 5: Commit**

```bash
git add session-host/src/journal.rs
git commit -m "Recover interrupted journal compression"
```

### Task 5: Enforce Physical `DROP_OLDEST` Retention

**Files:**

- Modify: `session-host/src/journal.rs:18-512`
- Test: `session-host/src/journal.rs:1407-1580`

**Step 1: Write the failing retention tests**

Use small limits and deterministic incompressible payloads to produce multiple
published closed segments. After `finish_maintenance`, assert that:

- the oldest closed prefix was deleted;
- remaining segment numbers are contiguous;
- the active segment was retained even when it alone exceeds the maximum;
- `read_after` below the retained floor returns `RetentionGap` with the first
  actual remaining event ID.

Add a pure planning assertion:

```rust
assert_eq!(
    retention_deletions(&[
        SegmentSize::closed(1, 40),
        SegmentSize::closed(2, 40),
        SegmentSize::active(3, 40),
    ], 80),
    [1],
);
```

**Step 2: Run the module tests and verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: no files are deleted and the retained floor remains the original
first event.

**Step 3: Implement physical-size planning and deletion**

After compression reconciliation, collect one published physical size per
logical segment. Compute the oldest closed prefix needed to bring the sum under
`journal_max_bytes`, stopping when only the active segment remains. Delete in
ascending number order and update the running total only after successful
removal.

Use ordinary loops, checked size addition, and no persistent index or retained
floor metadata.

**Step 4: Add deterministic deletion-failure and retry coverage**

Introduce a private maintenance filesystem seam for `rename` and `remove_file`.
A fake implementation fails the first deletion with `PermissionDenied` and
succeeds on the next reconciliation. Assert that appends and rotation still
succeed, the first maintenance pass records the failure, and a later pass
removes the same oldest segment.

Production uses a zero-state real filesystem implementation. Do not add a
public test-only method.

**Step 5: Run the module tests and verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: all retention, failure-retry, and existing CBOR tests pass.

**Step 6: Commit**

```bash
git add session-host/src/journal.rs
git commit -m "Retain bounded session journal history"
```

### Task 6: Make Reads Resilient to Concurrent Maintenance

**Files:**

- Modify: `session-host/src/journal.rs:327-554`
- Test: `session-host/src/journal.rs:1407-1580`

**Step 1: Write failing replacement and deletion race tests**

Add private test hooks around snapshot discovery so tests can deterministically:

- publish `.cbor.zst` while the raw file still exists;
- delete the selected oldest segment after discovery but before open;
- retain later segments and advance the first available event.

Assert that a replacement returns each event once and that deletion retries a
fresh snapshot and returns a gap rather than `NotFound`.

**Step 2: Run the module tests and verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: duplicate representations or `NotFound` escape from `read_after`.

**Step 3: Retry only maintenance races**

Split `read_after` into one snapshot attempt plus a bounded outer retry. Retry
when opening a discovered path returns `ErrorKind::NotFound`; do not retry CBOR,
Zstandard, ordering, or other I/O errors. Rediscovery recalculates
`firstAvailableEventId`, which naturally returns `RetentionGap` for a reader
that fell behind.

Keep raw-copy preference while raw and compressed published names coexist. Do
not add a lock shared with the writer.

**Step 4: Run the module tests and verify GREEN**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: concurrent maintenance tests and all reader/recovery tests pass.

**Step 5: Commit**

```bash
git add session-host/src/journal.rs
git commit -m "Handle journal maintenance read races"
```

### Task 7: Integrate Limits and Maintenance With the Unix Host

**Files:**

- Modify: `session-host/src/platform/unix.rs:70-165`
- Modify: `session-host/src/platform/unix.rs:435-468`
- Modify: `session-host/tests/unix_process_host.rs`
- Modify: `session-host/README.md`
- Modify: `session-host/protocol/README.md:3-50`

**Step 1: Write a failing host-level rotation test**

Extend the Unix test launcher to pass additional `session-host` options. Start
a short-lived command that emits enough deterministic bytes to cross a small
segment threshold, then assert after host exit that:

- at least one closed `.cbor.zst` and one active `.cbor` exist;
- `journal::read_after` returns the complete ordered PTY output;
- metadata's current `activeSegment` matches the writer's latest segment while
  that field remains in protocol v1.

**Step 2: Run the module tests and verify RED**

Run `make run-test MODULE=session-host TEST='*'`.

Expected: the host ignores the CLI limits and produces only one segment.

**Step 3: Wire production configuration and shutdown**

Construct `JournalConfig` from `SessionOptions`:

```rust
JournalConfig {
    durability: Durability::Buffered,
    segment_max_bytes: options.journal_segment_bytes,
    journal_max_bytes: options.journal_max_bytes,
}
```

After each append, set `metadata.active_segment` from
`journal.active_segment_number()` before the existing metadata write. After the
final journal flush and after no more appends can occur, call
`finish_maintenance()` and propagate its error through `HostError::Journal`.

**Step 4: Document configuration and operational semantics**

Document the two CLI flags and defaults in `session-host/README.md`. Clarify in
the protocol README that physical retention is asynchronous, deletes only
closed prefixes, may temporarily exceed its limit, and derives the gap floor
from the first remaining record. Do not alter protocol fixtures or journal
version 1 bytes.

**Step 5: Run focused and full development verification**

Run:

```bash
make run-test MODULE=session-host TEST='*'
mvn verify -Pdev -T 4
git diff --check
```

Expected: every command exits zero; Maven reports `BUILD SUCCESS`; protocol
fixtures remain unchanged.

**Step 6: Commit**

```bash
git add session-host/src/platform/unix.rs session-host/tests/unix_process_host.rs \
  session-host/README.md session-host/protocol/README.md
git commit -m "Integrate bounded session journals"
```

### Task 8: Finish the Dedicated Task Worktree

**Files:**

- Delete: `docs/plans/current-work/native-session-host/journal-retention/TASK.md`
- Modify: `docs/plans/current-work/native-session-host/TASK.md`

**Step 1: Apply the project review rules**

Read `docs/reviews/RULES.md`, review the complete branch diff against
`origin/main`, and fix every blocking finding through the same TDD cycle.

**Step 2: Re-run completion verification**

Run outside the sandbox:

```bash
make run-test MODULE=session-host TEST='*'
mvn verify -Pdev -T 4
git diff --check origin/main...HEAD
```

Expected: all commands succeed with no unreviewed blocking issue.

**Step 3: Remove the completed leaf from the task tree**

Delete the leaf directory and remove its link from the parent `TASK.md`. Keep
the approved design and this implementation plan under `docs/plans/`.

**Step 4: Squash unique task commits**

Squash every commit after `origin/main` into exactly one commit with this
single-line subject:

```text
Add bounded session journal retention [task: native-session-host/journal-retention]
```

Verify that the squashed commit includes the claim history's net task-tree
completion, implementation, tests, and documentation, but no unrelated files.

**Step 5: Transfer and verify on `main`**

Cherry-pick the squashed commit onto `main`, never merge. Run `make test` on
`main` outside the sandbox. If a task-related failure is fixed, create a
follow-up commit using the exact same subject so it can be squashed later.

**Step 6: Remove the completed worktree and branch**

After the cherry-pick and `make test` succeed, remove
`.worktrees/session-journal-retention-32cbac98` and delete
`codex/session-journal-retention-32cbac98`. Confirm with `git worktree list`
that the worktree is absent and with `git status --short` that `main` is clean.

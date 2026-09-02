# Descendant Absence Confirmation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep the session host alive until three consecutive process-table observations confirm that no PTY descendants remain.

**Architecture:** Add a small private confirmation state machine beside `wait_for_descendants`. The wait loop feeds each successful liveness observation into it; live observations reset the count, while the third consecutive empty observation completes the wait. Platform-specific descendant discovery and signaling remain unchanged.

**Tech Stack:** Rust standard library, existing Unix process tracker, Cargo test harness

---

### Task 1: Add deterministic absence-confirmation coverage

**Files:**
- Modify: `session-host/src/platform/unix.rs:1262`

**Step 1: Write the failing test**

Add this test to the existing `tests` module:

```rust
#[test]
fn requires_three_consecutive_empty_descendant_observations() {
    let mut confirmation = DescendantAbsenceConfirmation::new();

    assert!(!confirmation.observe(false));
    assert!(!confirmation.observe(false));
    assert!(!confirmation.observe(true));
    assert!(!confirmation.observe(false));
    assert!(!confirmation.observe(false));
    assert!(confirmation.observe(false));
}
```

Here `true` means that the current process-table observation found a live descendant.

**Step 2: Run the focused test to verify it fails**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: compilation fails because `DescendantAbsenceConfirmation` does not exist.

### Task 2: Implement the confirmation policy

**Files:**
- Modify: `session-host/src/platform/unix.rs:31`
- Modify: `session-host/src/platform/unix.rs:814`

**Step 1: Add the minimal policy implementation**

Add the confirmation count and private state:

```rust
const DESCENDANT_ABSENCE_CONFIRMATIONS: usize = 3;

struct DescendantAbsenceConfirmation {
    consecutive_empty: usize,
}

impl DescendantAbsenceConfirmation {
    fn new() -> Self {
        Self {
            consecutive_empty: 0,
        }
    }

    fn observe(&mut self, live: bool) -> bool {
        if live {
            self.consecutive_empty = 0;
            return false;
        }
        self.consecutive_empty += 1;
        self.consecutive_empty >= DESCENDANT_ABSENCE_CONFIRMATIONS
    }
}
```

Instantiate it before the wait loop and return only when `observe(is_live)` confirms absence. Continue sleeping between observations that do not complete the wait. Propagate `is_live` errors exactly as today.

**Step 2: Run the focused tests to verify they pass**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: all Rust unit and Unix process-host tests pass, including the new deterministic policy test and `keeps_detached_pty_descendant_controllable_after_its_leader_exits`.

**Step 3: Stress the formerly flaky end-to-end scenario**

Use the repository-pinned Cargo toolchain and cache paths to run only the detached-descendant test repeatedly outside the sandbox. Run at least 100 iterations and stop on the first failure.

Expected: every iteration passes.

### Task 3: Verify and commit the fix

**Files:**
- Modify: `session-host/src/platform/unix.rs`

**Step 1: Check formatting and the complete development build**

Run outside the sandbox:

```bash
cargo fmt --manifest-path session-host/Cargo.toml --check
mvn verify -Pdev -T 4
```

Expected: formatting check and the full reactor verification pass.

**Step 2: Review the final diff**

Run:

```bash
git diff --check
git diff -- session-host/src/platform/unix.rs
```

Expected: only the confirmation policy and its deterministic test are present.

**Step 3: Commit the implementation**

```bash
git add session-host/src/platform/unix.rs
git commit -m "Confirm descendant absence before session host exit"
```

**Step 4: Run the required post-commit test suite**

Run outside the sandbox:

```bash
make test
```

Expected: the full project test suite passes.

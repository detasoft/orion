# Session Host Shared Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Share pinned Rust and Cargo downloads across worktrees and provide one explicit goal that prefetches every locked crate.

**Architecture:** Make derives a versioned user cache directly from `HOME` and keeps Cargo compilation output under the current worktree. Maven delegates those locations to Make instead of maintaining duplicate properties or overrides.

**Tech Stack:** GNU Make, Maven, Cargo, JUnit 5, AssertJ

---

### Task 1: Specify shared-cache Make behavior

**Files:**
- Modify: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`
- Test: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`

**Step 1: Add a failing prefetch test**

Create an isolated fake `HOME` containing executable fake `cargo` and `rustc`
at the expected version-and-host-specific user-cache path. Run
`session-host-prefetch` and assert that Cargo receives `fetch --locked` with
`CARGO_HOME` below the fake user's cache rather than below the worktree.

**Step 2: Run the focused test and verify RED**

Run outside the sandbox:

```bash
make run-test MODULE=tests/test-support TEST='SessionHostMakefileTest#prefetchesLockedDependenciesIntoSharedUserCache'
```

Expected: FAIL because `session-host-prefetch` and the user-scoped cache do not
exist yet.

**Step 3: Add a failing worktree-isolation test**

Use the same fake toolchain to run `session-host-test`. Assert that Cargo uses
the shared user `CARGO_HOME`, receives `test --locked`, and receives a
`CARGO_TARGET_DIR` below the temporary worktree's `.orion-cache`.

**Step 4: Run the focused test and verify RED**

Run outside the sandbox:

```bash
make run-test MODULE=tests/test-support TEST='SessionHostMakefileTest#usesSharedDownloadsAndWorktreeLocalCompilationArtifacts'
```

Expected: FAIL because the current Makefile resolves both locations below the
worktree.

### Task 2: Implement the smallest Make and Maven change

**Files:**
- Modify: `session-host/Makefile`
- Modify: `session-host/pom.xml`
- Modify: `session-host/README.md`

**Step 1: Make the toolchain and Cargo home user-scoped**

Derive `SESSION_HOST_TOOLCHAIN_DIR` directly below
`$(HOME)/.cache/orion/session-host/rust-toolchains`, retaining the Rust version
and host triple in the directory name. Remove `ORION_CACHE_ROOT` and
`SESSION_HOST_TOOLCHAIN_CACHE`.

**Step 2: Keep compilation worktree-local without a public override**

Remove `SESSION_HOST_CARGO_TARGET`. Pass
`$(CURDIR)/.orion-cache/session-host-cargo` directly as `CARGO_TARGET_DIR` to
Cargo and use the same path when copying the release binary.

**Step 3: Add explicit dependency prefetching**

Add the phony `session-host-prefetch` goal depending on
`session-host-prepare`. Invoke the pinned Cargo with the same `RUSTUP_HOME` and
`CARGO_HOME` as other goals and run `fetch --locked` from `session-host`.

Protect the shared toolchain readiness check, bootstrap, and version validation
with the host's advisory file lock: `lockf` on Darwin and `flock` on Linux.
Validate and repair partial toolchains while holding the lock. Let the kernel
release ownership after normal exit, failure, signal, crash, or `SIGKILL`; do
not implement owner files, stale-lock reclamation, or recursive cleanup.

**Step 4: Remove duplicate Maven configuration**

Delete the cache properties and the corresponding Make command-line arguments
from `session-host/pom.xml`. Preserve the native-resource output argument,
which is the actual Maven-to-Make output contract. Remove any other property
proved unused in the repository.

**Step 5: Document the new preparation workflow**

Update `session-host/README.md` to describe the user-scoped cache,
worktree-local compilation artifacts, and `make session-host-prefetch`.

**Step 6: Run focused tests and verify GREEN**

Add concurrent fake-installer coverage before GREEN. Start a second worktree
after the first installer publishes its executables and verify that it still
waits for completed validation. Terminate a lock owner abnormally and verify a
later prepare acquires the released advisory lock. Seed an executable-only
partial toolchain and verify the pinned installer repairs it.

Run outside the sandbox:

```bash
make run-test MODULE=tests/test-support TEST='SessionHostMakefileTest'
```

Expected: PASS.

### Task 3: Verify the real build paths

**Files:**
- Verify: `session-host/Makefile`
- Verify: `session-host/pom.xml`
- Verify: `session-host/README.md`
- Verify: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`

**Step 1: Prefetch the real locked dependencies**

Run outside the sandbox:

```bash
make session-host-prefetch
```

Expected: PASS and populate the versioned user cache.

**Step 2: Run routine development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: PASS.

**Step 3: Review and commit**

Confirm the diff has one canonical cache model, no redundant cache properties
or public override variables, and no unrelated changes. Commit the direct
change with a descriptive single-line subject.

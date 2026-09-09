# Shared Rust Bootstrap Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace Orion's project-local Rust bootstrap with an explicit four-line rustup installer and shared Cargo state under `~/.cargo`.

**Architecture:** Keep the existing root Make targets as the build contract, but make them invoke a ready `~/.cargo/bin/cargo` directly and share only Cargo build artifacts. Installation is a separate manual action; a missing Cargo executable fails the requested build without downloads or fallback behavior.

**Tech Stack:** POSIX shell, GNU/BSD Make, rustup, Cargo, Maven AntRun.

---

### Task 1: Replace the Rust bootstrap machinery

**Files:**

- Modify: `Makefile`
- Delete: `session-host/Makefile`
- Create: `session-host/install-rust.sh`
- Modify: `session-host/pom.xml`
- Modify: `session-host/README.md`
- Delete: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`

**Step 1: Confirm the current partial deletion is not buildable**

Run:

```bash
make -n session-host-test
```

Expected: Make fails because the root file still includes the deleted
`session-host/Makefile`.

**Step 2: Add the explicit installer**

Create an executable `session-host/install-rust.sh` with no more than five
physical lines. Use strict shell failure handling, read `channel` from the
adjacent `rust-toolchain.toml`, and run the official rustup installer with:

```text
CARGO_HOME=$HOME/.cargo
RUSTUP_HOME=$HOME/.rustup
profile=minimal
default-toolchain=<pinned channel>
```

The script is manual setup only. No Make or Maven target invokes it.

**Step 3: Put the stable Cargo targets in the root Makefile**

Remove `session-host-prepare` and the deleted Makefile include. Define the
existing `session-host-build`, `session-host-test`, and
`session-host-fixtures` targets directly. They must:

- invoke `$(HOME)/.cargo/bin/cargo` without fallback detection;
- set `CARGO_TARGET_DIR` to `$(HOME)/.cargo/orion/session-host-target` by
  default so worktrees reuse compiled artifacts;
- retain `--locked`, release build/fixture behavior, native resource placement,
  and executable permissions;
- derive the host triple from Cargo only while running `session-host-build`,
  so unrelated Make targets do not require Rust.

**Step 4: Remove Maven cache plumbing and obsolete tests**

Remove only the project-local toolchain/cache properties and arguments from
`session-host/pom.xml`. Delete the focused test that exists solely for the
removed Make parser. Do not introduce aliases, compatibility properties, or a
replacement bootstrap abstraction.

**Step 5: Update setup documentation**

Replace the hermetic/project-local bootstrap description in
`session-host/README.md` with the explicit `./session-host/install-rust.sh`
step, standard user homes, shared target-cache location, and the fact that
builds fail when Cargo is missing.

**Step 6: Verify the replacement**

Run outside the sandbox where required:

```bash
sh -n session-host/install-rust.sh
test "$(wc -l < session-host/install-rust.sh)" -le 5
make session-host-test
make test
git diff --check
```

Expected: shell validation and line bound pass, native tests pass using the
ready shared Cargo installation, and all Maven modules pass.

**Step 7: Prepare one direct-change commit**

Commit the implementation as one logical commit with a single-line subject,
leaving the worktree clean. This is a direct change: do not create a task or
use a task-tagged subject.

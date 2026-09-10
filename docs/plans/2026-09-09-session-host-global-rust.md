# Session Host Global Rust Implementation Plan

**Goal:** Replace the custom per-checkout Rust bootstrap with one Make goal that installs and reuses the standard global Rustup and Cargo directories.

**Architecture:** `session-host-install-rust` owns first-time Rustup installation in `$HOME/.rustup` and `$HOME/.cargo`. All session-host Cargo commands depend on that goal and run inside `session-host`, where `rust-toolchain.toml` selects the one pinned project toolchain; Maven only delegates to Make.

**Tech Stack:** GNU Make, POSIX shell, Rustup, Cargo, Maven, JUnit 5, AssertJ

---

### Task 1: Specify the global installation behavior

**Files:**
- Modify: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`
- Test: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`

**Step 1: Replace the legacy toolchain-file parser fixtures**

Create an executable fake Rustup installer and a fake `curl` in a temporary
directory. The installer must log its invocation and create an executable
`$CARGO_HOME/bin/cargo` that logs its working directory, `CARGO_HOME`,
`RUSTUP_HOME`, and arguments.

**Step 2: Add the first-time installation test**

Run `make --no-print-directory session-host-install-rust` with an isolated
temporary `HOME` and the fake tools first on `PATH`. Assert that the installer
runs with the minimal profile and `--default-toolchain none`, the Cargo proxy
is created below `$HOME/.cargo`, and Cargo is invoked from `session-host` so
`rust-toolchain.toml` is authoritative.

**Step 3: Add the cross-worktree reuse test**

Create two temporary checkout-shaped roots containing the Makefile and
`session-host/rust-toolchain.toml`, give them the same temporary `HOME`, and run
the install goal in both. Assert that the installer runs once while both goals
invoke the same `$HOME/.cargo/bin/cargo`.

**Step 4: Run the tests and verify RED**

Run outside the sandbox:

```bash
make run-test MODULE=tests/test-support TEST='SessionHostMakefileTest'
```

Expected: FAIL because `session-host-install-rust` does not exist and the
current Makefile uses its private bootstrap cache.

### Task 2: Replace the custom Make bootstrap

**Files:**
- Modify: `session-host/Makefile`
- Modify: `Makefile`
- Test: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`

**Step 1: Define only the standard global locations**

Keep the native-resource output variable. Define Cargo as
`$(HOME)/.cargo/bin/cargo` and Rustup state as `$HOME/.rustup`; remove the
version parser, downloaded-installer metadata, checksums, host platform table,
custom cache roots, and all derived private toolchain paths.

**Step 2: Add the install goal**

Add `session-host-install-rust`. If the Cargo proxy is absent, run the official
Rustup installer with `--profile minimal`, `--no-modify-path`, and
`--default-toolchain none`, explicitly using `$HOME/.cargo` and
`$HOME/.rustup`. Then run `cargo --version` from `session-host` so the goal
installs and selects the toolchain pinned by `rust-toolchain.toml`.

Do not add custom locks, installer archives, checksums, version parsing,
platform cases, fallback paths, or public cache overrides.

**Step 3: Route every Cargo operation through that installation**

Make `session-host-build`, `session-host-test`, and
`session-host-fixtures` depend on `session-host-install-rust`. Invoke the same
global Cargo executable from `session-host` without a custom target directory.
For packaging, read the host triple from `cargo -vV` and copy
`session-host/target/release/session-host` into the existing
`META-INF/orion/native/session-host/<host>/session-host` resource path.

**Step 4: Update the root Make goal list**

Replace `session-host-prepare` with `session-host-install-rust` in the root
Makefile's reserved goals. Do not add aliases for the removed goal.

**Step 5: Run the focused tests and verify GREEN**

Run outside the sandbox:

```bash
make run-test MODULE=tests/test-support TEST='SessionHostMakefileTest'
```

Expected: PASS.

### Task 3: Remove Rust configuration from Maven

**Files:**
- Modify: `session-host/pom.xml`

**Step 1: Remove duplicate cache configuration**

Delete `session-host.toolchain.cache`, `session-host.cargo.target`, and the
unused `session-host.binary.name` property. Delete the corresponding Make
arguments from both Exec Maven Plugin executions.

Preserve only `session-host.native.resources` and its build-goal argument,
because that is the Maven packaging output contract. Maven must contain no
Rust version, Rustup, Cargo home, toolchain, or cache selection.

**Step 2: Verify the Maven model**

Run outside the sandbox:

```bash
mvn validate -Pdev -pl session-host -am
```

Expected: BUILD SUCCESS.

### Task 4: Verify and prepare the implementation

**Files:**
- Verify: `Makefile`
- Verify: `session-host/Makefile`
- Verify: `session-host/pom.xml`
- Verify: `tests/test-support/src/test/java/pro/deta/orion/makefile/SessionHostMakefileTest.java`

**Step 1: Exercise the real install goal**

Run outside the sandbox:

```bash
make session-host-install-rust
```

Expected: the standard global Cargo installation is reused or installed and
reports Cargo 1.97.0 selected by `session-host/rust-toolchain.toml`.

**Step 2: Run normal development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 3: Self-review the complete diff**

Confirm there is one installation goal, one exact toolchain pin, no custom Rust cache model,
no Maven toolchain logic, and no compatibility alias.

### Task 5: Align user documentation after integration

**Files:**
- Modify on `main`: `session-host/README.md`

**Step 1: Replace the obsolete build description**

After the reviewed implementation is integrated, update the Build section to
state that `make session-host-install-rust` installs standard global Rustup,
that all worktrees reuse `$HOME/.cargo` and `$HOME/.rustup`, and that
`rust-toolchain.toml` owns the exact project toolchain.

# Session Host Rust Toolchain Pin Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `session-host/rust-toolchain.toml` the only exact Rust compiler
version declaration used by every session-host build entry point.

**Architecture:** Keep the standard Rust toolchain file authoritative for direct
Cargo use. Have the existing Make bootstrap extract its exact channel with
portable host tooling, while Cargo's `rust-version` remains an independently
maintained compatibility floor.

**Tech Stack:** GNU Make, POSIX shell utilities, Maven, Cargo, rustup.

---

### Task 1: Consolidate exact toolchain selection

**Files:**

- Modify: `session-host/Makefile:1-5`
- Modify: `session-host/pom.xml:17-23`
- Modify: `session-host/README.md:54-60`

**Step 1: Record the conflicting declarations**

Run:

```bash
rg -n 'rust.version|SESSION_HOST_RUST_VERSION|channel =|rust-version' \
  session-host/pom.xml session-host/Makefile session-host/rust-toolchain.toml \
  session-host/Cargo.toml
```

Expected: the exact `1.97.0` pin appears in the POM, Makefile, and toolchain
file, while Cargo declares the `1.97` compatibility floor.

**Step 2: Make the standard toolchain file authoritative**

Remove the unused `rust.version` property from `session-host/pom.xml`. Replace
the literal `SESSION_HOST_RUST_VERSION` default in `session-host/Makefile` with
an immediately evaluated value extracted from the `channel` field in
`session-host/rust-toolchain.toml`. Reject a missing, empty, or ambiguous value
with a clear Make error. Do not introduce Python, Maven plugins, or an external
TOML parser for this fixed repository-owned file.

**Step 3: Update the build documentation**

State that `rust-toolchain.toml` owns the exact hermetic build version, the
Make bootstrap consumes that pin, and `Cargo.toml#rust-version` expresses only
the minimum supported compiler version.

**Step 4: Verify Make observes the canonical pin**

Run a non-mutating Make database inspection and confirm that the resolved
`SESSION_HOST_RUST_VERSION` equals the `channel` value in
`session-host/rust-toolchain.toml`. Temporarily malformed-file testing may be
performed only on a disposable copy or with an alternate Make variable; do not
modify the tracked toolchain file merely to test an error path.

**Step 5: Run focused verification**

Run outside the sandbox:

```bash
mvn test -Pdev -T 4 -q -pl session-host -am
```

The existing baseline may fail only in
`keeps_detached_pty_descendant_controllable_after_its_leader_exits`; report it
separately and verify that toolchain preparation, compilation, and all other
session-host tests complete as before.

**Step 6: Commit the implementation**

```bash
git add session-host/Makefile session-host/pom.xml session-host/README.md
git commit -m "Consolidate session host Rust toolchain pin"
```

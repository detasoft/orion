# Session Host Worktree Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make direct session-host Make targets reuse the same worktree-local cache as Maven across `clean`.

**Architecture:** Define a worktree-local `.orion-cache` root in `session-host/Makefile`. Derive the default Rust toolchain and Cargo target directories from it while preserving command-line overrides used by Maven and CI.

**Tech Stack:** GNU Make, Maven, Cargo

---

### Task 1: Align Make cache defaults with Maven

**Files:**
- Modify: `session-host/Makefile:1-5`

**Step 1: Run a failing configuration assertion**

Run a shell assertion over `make -pn session-host-prepare` that requires
`SESSION_HOST_TOOLCHAIN_CACHE` and `SESSION_HOST_CARGO_TARGET` to resolve below
the current worktree's `.orion-cache` directory.

Expected: FAIL because the Make defaults currently resolve below `target`.

**Step 2: Add the common cache-root variable**

Add:

```make
ORION_CACHE_ROOT ?= $(CURDIR)/.orion-cache
```

Derive both session-host cache defaults from it:

```make
SESSION_HOST_TOOLCHAIN_CACHE ?= $(ORION_CACHE_ROOT)/rust-toolchains
SESSION_HOST_CARGO_TARGET ?= $(ORION_CACHE_ROOT)/session-host-cargo
```

**Step 3: Run the configuration assertion again**

Expected: PASS with both paths below `<worktree>/.orion-cache`.

**Step 4: Verify cache reuse without network access**

Run `make session-host-build` with Cargo offline mode enabled against the
already-populated cache.

Expected: PASS without downloading the toolchain or crates.

**Step 5: Review the diff and working tree**

Confirm only the planned Makefile and plan documents changed, leaving existing
user changes unstaged.

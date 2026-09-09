# Shared Rust Bootstrap Design

## Goal

Replace the project-local Rust bootstrap and cache machinery with the standard
user installation under `~/.cargo` and `~/.rustup`. Session-host builds require
that installation to exist and fail normally when Cargo is unavailable.

## Design

Delete the session-host Make fragment, its custom rust-toolchain parser test,
the architecture/checksum download matrix, and the `session-host-prepare`
target. Keep `session-host-build`, `session-host-test`, and
`session-host-fixtures` as small root Makefile targets that invoke
`~/.cargo/bin/cargo` directly. Store Cargo build artifacts in the shared
`~/.cargo/orion/session-host-target` directory so worktrees reuse them.

Add `session-host/install-rust.sh` as an optional manual bootstrap command with
at most five physical lines. It reads the exact toolchain channel from
`rust-toolchain.toml` and invokes the official rustup installer with the
minimal profile. Builds never invoke this script implicitly and perform no
download or installation fallback.

Maven continues to call the existing Make targets, but no longer passes
project-local toolchain or target-cache properties. Update the session-host
README to describe the explicit setup command and shared locations.

## Failure and Verification

Missing `~/.cargo/bin/cargo` is an immediate build failure. The installer uses
strict shell error handling, while rustup owns platform detection and download
validation. Verify shell syntax and the line limit, then run the native test
target and the regular full-project test target.

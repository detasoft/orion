# Consolidate the Session Host Rust Toolchain Pin

Status: active
Owner: codex, session toolchain-pin-51d2, started 2026-09-02 22:50 Europe/Amsterdam.
Plan: ../../../2026-09-02-session-host-rust-toolchain-pin.md

Make `rust-toolchain.toml` the single exact Rust compiler pin used by direct
Cargo development and the hermetic Maven/Make build.

## Scope

- Remove the unused Maven `rust.version` property.
- Derive the Make bootstrap, cache key, and version checks from the exact
  `channel` in `session-host/rust-toolchain.toml` without adding a new tool
  dependency.
- Keep Cargo's `rust-version` as an independent minimum supported Rust version.
- Update build documentation to distinguish the exact build pin from the Cargo
  compatibility floor.
- Verify direct Cargo and Maven-driven session-host builds use the same exact
  toolchain and fail clearly if the canonical pin cannot be read.

## Boundary

Do not change the selected Rust or Rustup versions, dependency versions,
runtime code, journal/control formats, or artifact layout.

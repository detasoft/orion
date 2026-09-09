# Session Host Global Rust Design

The session-host build uses the standard Rustup installation in
`$HOME/.rustup` and `$HOME/.cargo`. Because those directories belong to the
user rather than a checkout, `main` and every linked worktree automatically
reuse the same Rust installation and Cargo downloads.

Add one `session-host-install-rust` Make goal. When
`$HOME/.cargo/bin/cargo` is absent, the goal runs the official Rustup installer
with the minimal profile and no default toolchain. Build, test, and fixture
goals depend on it and invoke `$HOME/.cargo/bin/cargo` from `session-host`.
The existing `rust-toolchain.toml` remains the only exact toolchain pin, so the
first Cargo invocation installs and selects that one pinned toolchain.

Remove custom Rustup downloads, checksums, platform tables, version parsing,
cache roots, toolchain paths, environment overrides, and prepare logic from
the Makefile. Query Cargo for its host triple only when placing the built
binary in the existing native-resource layout. Cargo compilation output stays
under the current worktree's `session-host/target` directory.

Maven continues to invoke the Make build and test goals and to supply only the
native-resource destination needed for packaging. Remove its Rust toolchain
and Cargo cache properties and arguments. No Rust installation or toolchain
selection logic remains in Maven.

Exercise the install goal with an isolated fake `HOME` and installer so tests
do not modify the developer's real Rust installation. Cover first-time
installation and reuse of the same global Cargo installation by another
worktree. Verify the focused Make behavior and the normal development build.

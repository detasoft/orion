# Session Host Worktree Cache Design

The session-host build must download its pinned Rust toolchain and Cargo crates
at most once per Git worktree. Maven `clean` must not remove either those
downloads or Cargo compilation artifacts.

Use `<worktree>/.orion-cache` as the default cache root for all session-host
Make targets. Store the versioned Rust toolchain, including its `CARGO_HOME`
registry and crate archives, below `rust-toolchains`. Store Cargo compilation
artifacts below `session-host-cargo`.

Maven already passes these paths explicitly. Align the Makefile defaults with
the Maven paths so direct `make session-host-build`, `make session-host-test`,
and `make session-host-fixtures` have the same clean-resistant behavior.

Keep caches worktree-local. This avoids cross-worktree build serialization and
prevents concurrent branches from racing over the same final native binary.

Verify the configuration by inspecting Make's resolved variables, checking
that the cache root is outside Maven's `target` directories, and running the
session-host build offline from a populated worktree cache.

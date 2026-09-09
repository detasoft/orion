# Session Host Shared Cache Design

The session-host build must reuse one user-scoped Rust toolchain and Cargo
download cache across every worktree owned by that user. Cargo compilation
artifacts remain worktree-local so concurrent branches neither serialize their
builds nor race over a final native binary.

Store the versioned toolchain below
`$(HOME)/.cache/orion/session-host/rust-toolchains/<rust-version>-<host-triple>`.
Its isolated `CARGO_HOME` contains the Cargo registry, crate archives, and
sources shared by all worktrees. Continue to store compilation artifacts below
`<worktree>/.orion-cache/session-host-cargo` and packaged native resources below
the Maven module's build directory.

Make owns these cache locations. Remove the redundant Maven cache properties
and command-line Make assignments, along with the public
`ORION_CACHE_ROOT`, `SESSION_HOST_TOOLCHAIN_CACHE`, and
`SESSION_HOST_CARGO_TARGET` override paths. Keep only variables that express a
toolchain pin, a derived internal path, or the Maven native-resource output
contract.

Add `session-host-prefetch` as the explicit network preparation goal. It first
bootstraps and validates the pinned Rust toolchain, then runs
`cargo fetch --locked` so later builds find every locked crate in the shared
user cache. Existing build, test, and fixture goals retain their current
`--locked` behavior: a fresh developer or CI environment can still fetch a
missing dependency when prefetch was not run, while prewarmed worktrees reuse
the shared cache automatically.

Because the toolchain cache is shared, serialize its readiness check,
installation, and validation with an OS-owned advisory lock: `lockf` on Darwin
and `flock` on Linux. The kernel releases these locks when a process exits or is
killed, so bootstrap does not create persistent owner or stale-lock state.
Treat an incomplete or incorrectly versioned cached toolchain as unprepared and
repair it with the pinned installer while still holding the lock. Cargo fetch,
build, test, and fixture execution remain outside the toolchain lock.

Verify the design with executable Makefile tests using an isolated fake home
and fake pinned toolchain. Cover both the prefetch command and the separation
between the shared Cargo home and worktree-local compilation target. Also cover
late concurrent starters, abnormal lock-owner termination, and incomplete
toolchain recovery. Run the real prefetch goal and the normal project
verification after implementation.

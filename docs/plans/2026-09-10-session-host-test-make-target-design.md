# Session Host Test Make Target Design

## Goal

Restore a root `make session-host-test` entry point and make routine `make test`
run the Rust session-host suite as well as the Maven suite.

## Design

Add one phony `session-host-test` target to the root `Makefile`. The target
depends on the existing `rust-install` bootstrap and runs
`cargo test --locked` from `session-host` with the project-managed Cargo binary.

Make the existing `test` target depend on `session-host-test`. GNU Make will run
the Rust suite before the existing Maven recipe; a Rust failure prevents Maven
from starting and is returned as the overall test failure. Keep the Maven
command unchanged.

Do not restore `session-host/Makefile`, add another script, duplicate Rust setup,
or add source-scanning tests for the Makefile. The target itself is the
behavioral verification boundary.

## Verification

Before implementation, `make session-host-test` must fail because the target is
absent. After implementation, run `make session-host-test`, then `make test`,
and confirm both Rust and Maven suites pass. Use `make -n test` only as a
supplementary ordering check.

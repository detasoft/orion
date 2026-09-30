# Restore build cache for standard test goals

- Owner: codex, session restore-test-cache-20260930, branch `codex/restore-test-cache-20260930`,
  worktree `.worktrees/restore-test-cache-20260930`, started 2026-09-30 16:13 Europe/Amsterdam.

## Requirements

- Re-enable Maven build cache for `make test` and `make test-all`, including focused runs.
- Keep each goal's current Maven phase, profiles, module and test selection, logging, and quiet output.
- Accept the loss of random class-directory isolation for simultaneous runs in the same checkout.
- Preserve unrelated build goals and workspace changes.

## Design

Use Maven's default `classes` and `test-classes` output directories, which the existing build-cache
configuration attaches. Remove the random suffix and explicit cache-disable flag from the two test goals.
Leave other Maven invocations unchanged.

## Implementation plan

1. Make the minimal `Makefile` edit.
2. Inspect dry-run commands for full and focused `test` and `test-all` invocations.
3. Run affected real goals and check that the second run reuses the build cache without losing test coverage.

## Acceptance criteria

- `make test` and `make test-all` invoke Maven with build cache enabled and ordinary class directories.
- Full and focused invocations retain their selection and lifecycle behavior.
- Required checks pass, or unrelated concurrent failures are identified without modifying their work.

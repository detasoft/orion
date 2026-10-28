# Establish a Reproducible Release Build

Status: todo

## Requirements

Establish reproducible build and test evidence for one exact candidate revision
and the host/toolchain combinations intended for the preview. Identify the
existing CI and its actual coverage before adding missing automation.

## Design Decisions

- Use existing Make goals and pinned toolchains. A clean checkout using a shared
  dependency cache is not evidence of installation on a clean machine.
- Run frontend package operations separately from Maven work in the same
  frontend directory to avoid concurrent mutation of dependencies and outputs.
- Record failed and skipped checks as such. A diagnostic package built with
  tests disabled does not satisfy release build acceptance.
- The full local `make test` passed after `6b4c36f3`; the clean candidate's
  `make test-all` and `make dist` still need verification.

## Implementation Plan

1. Select the candidate SHA and record OS, architecture, JDK, Maven, pinned
   frontend Node/npm, Rust toolchain, dependency caches, and external services.
2. From a clean checkout, run `make test` and `make test-all`. Record command
   results, durations, failures, and skipped scenarios against that SHA.
3. Run `npm ci`, `npm test`, and `npm run build` in `net/frontend/ui` with the
   documented frontend toolchain and no concurrent Maven frontend operation.
4. Run `make dist` with tests enabled. Record artifact checksums and packaging
   results; coordinate archive/startup defects with the installation task.
5. Inspect repository workflows, remote status/check providers, branch rules,
   and any external CI. The 28 October inspection found no GitHub workflows or
   reported checks for remote `b374070e`; that did not rule out external CI.
   Extend the existing automation, or add the minimum missing build/test gate,
   only after determining the actual coverage.
6. Reproduce and resolve candidate failures through the responsible component;
   rerun affected checks and retain the final complete candidate result.

## Dependencies

No other release leaf is required to begin the baseline. Final acceptance
depends on resolution of defects exposed by the transport, installation, or
dependency checks when those defects prevent a complete candidate build.

## Acceptance Criteria

- The clean candidate passes unit, integration, frontend, and distribution
  builds on the declared environment; exclusions and supported platforms are
  explicit and no failed check is presented as passing.
- Evidence identifies the source SHA and artifact hashes and is reproducible
  without undocumented development state.
- Actual CI coverage is documented and the minimum release verification is
  automated; absence of one conventional workflow filename is not treated as
  evidence that all CI is absent.

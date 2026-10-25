# Remove avoidable work from cached full-reactor test runs

- Owner: codex, session 01a10b70-4984-7811-b5da-02fd849e4b35,
  branch `codex/test-cache-runtime-20261025`,
  worktree `.worktrees/test-cache-runtime-20261025`, started 2026-10-25 14:37 Europe/Amsterdam.

## Requirements

- Diagnose why repeated full-reactor `make test` remains expensive with the existing Maven build cache enabled.
- Measure a clean committed checkout independently of unrelated changes in the shared development checkout.
- Reuse valid successful results for unchanged inputs; retain all existing test coverage and failure propagation.
- Keep `TEST` as the explicit selector for fast targeted checks.
- Do not introduce Git-diff module selection, change module-selection semantics, or migrate the build to Gradle.
- Apply only the smallest optimization justified by measured evidence. Do not add repository scripts.
- Preserve production behavior, integration-test boundaries, and correctness of cache invalidation.

## Current model

The standard goal runs the complete Maven reactor through `package -Pdev -T 4 -q`.
The Maven build-cache extension is already enabled. Its local store is configured as
`.orion-build-cache`, with classes and test classes attached to cached outputs.
The goal acquires a lock under the common Git directory, shared by all worktrees.
The reactor includes JVM modules, a frontend build, Rust/session-host work, generated sources,
and executable packaging. Their actual repeated work and cache-hit behavior need measurement.

The separate full-suite runtime task retains its existing twenty-second acceptance target;
this task supplies evidence and removes one concrete cache-related source of avoidable work.

## Design

Use the existing full-reactor goal and existing diagnostic outputs. Separate initial checkout/cache
preparation, actual build execution, and lock waiting. Compare at least one initial full run with
three unchanged repeat runs, recording wall time, executed tests, cache hits/misses where observable,
and always-executed frontend, Rust, generation, or packaging work.

Identify the dominant avoidable cost before choosing a fix. Report baseline evidence and the proposed
smallest optimization before editing implementation. If all expensive work is required, report that
finding rather than adding a selector, bypassing tests, or forcing a speculative configuration change.

For any selected fix, explicitly account for source, resource, dependency, toolchain, test-selection,
and skip-flag invalidation to the extent affected by the change. Verify actual build behavior rather
than asserting build-file text. Reuse existing Make/Maven/Cargo/npm mechanisms and their existing owners.

## Implementation plan

1. Establish a clean baseline and collect initial and unchanged repeat-run measurements.
2. Attribute elapsed time to lock waiting, cache lookup/restoration, and actual repeated executions.
3. Select the smallest measured optimization and update this task's design if a concrete change is needed.
4. Implement that single optimization with proportionate behavior verification, including a meaningful
   invalidation or failure case chosen from the actual risk.
5. Repeat comparable full-reactor runs and report before/after timings and actual test/cache behavior.
6. Inspect the complete result for skipped coverage, unsafe cache reuse, new abstractions, and unrelated edits.

## Dependencies

- The existing Maven build-cache extension and the repository's documented development toolchain.
- Comparable timing measurements require no competing active Maven or Surefire execution.

## Acceptance criteria

- Initial and three unchanged repeat-run results are recorded with an exact base revision and usable logs.
- The dominant cost and actual cache reuse are explained with evidence, including any lock-wait contribution.
- A selected optimization demonstrates repeatable benefit without losing existing coverage or safe invalidation.
- Required verification passes; unrelated environmental failures are reported without modifying parallel work.
- If no safe optimization is supported by the evidence, return the verified diagnosis and its limits explicitly.

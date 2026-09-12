# Reduce the warm full-test runtime below twenty seconds

Status: paused

- Owner: codex, session test-runtime-6b8f, branch `codex/test-suite-runtime-6b8f`,
  worktree `.worktrees/test-suite-runtime-6b8f`, paused 2026-09-12 10:24 Europe/Amsterdam;
  next: determine a verified redesign of full-suite execution that preserves all
  tests and can close the measured 19-second gap from the fastest experiment.

## Requirements

- Make a complete warm-cache `make test` finish in at most 20 seconds on the
  current 16-logical-CPU development machine.
- Preserve the complete JVM and Rust test coverage executed by the baseline.
- Preserve production behavior and keep timeout, lifecycle, SSH, HTTP, Git,
  persistence, and recovery assertions meaningful.
- Measure only isolated runs with no competing Maven or Surefire process.

## Current model

`make test` runs the complete Maven reactor with four reactor threads. The
Maven test lifecycle also runs the `session-host` Rust tests. Surefire uses its
default single reusable fork per module and test classes run sequentially
inside that fork.

The observed successful report contains 2,183 JVM tests in 280 suites, plus 141
Rust tests. JVM suites account for about 118 seconds of cumulative execution.
The largest module totals are `agentd` (15.0 seconds), `agent-provisioning`
(14.3 seconds), `agent-session-server` (13.6 seconds), Git test support and
matrix modules (10.3 and 10.2 seconds), and `net/http-core` (9.6 seconds). The
largest individual test is an intentional two-second SSH watchdog scenario;
there is no single dominant test.

## Design

First establish three isolated warm baseline runs from the claimed revision and
retain their wall times, executed test counts, and slow-suite rankings. Increase
test-class concurrency through additional reusable Surefire forks while keeping
each fork internally sequential, so tests that mutate process-global state do
not begin sharing a JVM concurrently. Keep Maven reactor concurrency bounded so
the combined number of JVMs fits the available CPUs.

Shorten only intentional test-controlled waits where the tested timeout remains
observable and causally responsible for the result. Do not weaken production
timeouts, remove scenarios, exclude modules, move tests out of `make test`, or
replace live-boundary tests with mocks merely to meet the runtime target.

After each change, regenerate the ranking from Surefire XML. Stop once three
consecutive isolated warm runs pass in at most 20 seconds. If controlled forks
and artificial-wait reductions are insufficient, optimize the next measured
critical-path fixture locally; do not introduce a repository-wide fixture
framework or shared mutable server state.

## Implementation plan

1. Run three isolated warm `make test` baselines and record wall time, JVM and
   Rust test counts, failures, and the slowest suites and individual tests.
2. Benchmark the smallest bounded reusable-fork configuration without changing
   test semantics. Select the lowest concurrency that can satisfy the target
   reliably on 16 logical CPUs.
3. Re-run the complete suite and compare counts and failures with the baseline.
4. If the target is not met, reduce measured test-controlled timeout waits one
   at a time and run their focused module tests after each change.
5. If the target is still not met, optimize only the next measured critical-path
   test fixture, preserving its observable assertions and isolation.
6. Run three consecutive isolated warm `make test` executions and require each
   to finish in at most 20 seconds with no failures and no loss of tests.
7. Inspect the complete change for unnecessary configuration, abstractions,
   shared state, weakened assertions, skipped tests, and unrelated edits.

## Acceptance criteria

- Three consecutive isolated warm `make test` runs each complete in at most 20
  seconds.
- All baseline JVM and Rust tests still execute and pass; no test or module is
  excluded, disabled, or moved out of `make test`.
- No production timeout or production behavior changes solely for test speed.
- Changed focused goals and the complete `make test` goal pass.
- The final report identifies the actual optimizations, before/after timings,
  test counts, and any remaining environment sensitivity.

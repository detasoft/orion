---
name: orion-test-analysis
description: Use when analyzing Orion test duration history, overlapping tests, module/class/method timing, mixed outcomes, or differences between recorded runs.
---

# Orion Test Analysis

Use the read-only Make goals from the repository root. They compile two small
JDK-only sources when necessary; they do not run Maven or launch tests.
Implementation and goals live in `tests/test-duration-recorder` and
`tests/test-support/Makefile`.

## Select evidence

Start with `make test-runs N=10`. Pin `RUN=<id>` for reproducible follow-ups.
Defaults read new per-run files under `.test-durations/runs`; timestamped run
IDs determine recency. Custom non-timestamped filenames use modification time.
For supplied data, repeat `DATA=<file-or-directory>` on every command.

Do not read, import, concatenate, or scan the old monolithic history.
Explicit `DATA=<legacy-file>` reads only its last 8 MiB. Each query is limited
to 8 MiB per selected file and 64 MiB total. Reduce `N` when necessary; do not
silently substitute a different run or remove the bounds.

## Queries

All goals print terminal tables. Start with `make test-summary`: it includes
both module totals and the 20 longest individual test executions, sorted by
duration descending. No options are required.

| Goal | Result |
| --- | --- |
| `make test-summary` | Summary and longest tests from the latest run |
| `make test-summary N=10 BY=class` | Aggregate the last ten runs by class |
| `make test-summary N=10 BY=method` | Aggregate the last ten runs by method |
| `make test-find` | The 20 longest individual test executions |
| `make test-runs N=10` | Recent recorded runs |
| `make test-concurrency` | Observed test/process/thread peaks and busiest intervals |
| `make test-compare` | Mean-duration changes between the latest two runs |

For investigations, pin `RUN=<id>`; compare also accepts `BASE=<id>`.
Use `NAME=<literal substring>` to narrow tests, `MODULE=<substring>` for modules,
or `STATUS=FAILED` for recorded failures. `TOP` changes the default 20-row limit.
`MIN` is the minimum duration in milliseconds; concurrency accepts `AT=<ISO-instant>`.
These are optional refinements, not prerequisites. Grouping defaults to module;
`N=1` selects the latest run. Repeat `DATA` on every query using supplied data.

Tables show a bounded selection. Read the matched-record count and group count
before treating displayed rows as exhaustive. Longest-test rows are individual
executions, not method averages. Summary and comparison durations are milliseconds.
A missing group in a comparison does not prove deletion.

## Interpret results

- Preserve module-qualified class/method identities and cite selected run IDs.
- A `PARTIAL INPUT` warning means only part of the selected input was read;
  conclusions must be restricted accordingly.
- Recorded executions can include deliberately failing nested test fixtures.
  Use the build exit status and Surefire/Failsafe reports for the build verdict.
- `Completion: unknown` means files contain flushed test plans, not a durable
  whole-build completion marker. An absent test may still be running or unflushed.
- `WALL ms` spans observed test intervals, not the complete Maven invocation.
  Summed test durations can exceed wall time because tests overlap.
- Concurrency uses half-open intervals `[start, end)` and excludes skipped tests.
  The busiest intervals are a bounded selection, not a complete chronological trace.
- Known process/thread peaks use recorded identities. Old records lack them;
  zero known processes does not mean no JVM ran.
- Overlap is not CPU utilization, configured worker count, or proof of resource
  contention. Use existing JFR reports when CPU/allocation evidence is needed.
- Successful and failed executions of the same test identify candidates for
  investigation; they do not prove flakiness or the cause of a slowdown.

Report observations and uncertainties separately. Do not start another test run
merely to inspect existing data.

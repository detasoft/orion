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

| Goal | Useful options |
| --- | --- |
| `make test-runs` | `N=10` |
| `make test-summary` | `N=10 BY=module` or `BY=class` or `BY=method` |
| `make test-find` | `RUN=... NAME=substring MODULE=substring STATUS=FAILED MIN=100 TOP=20` |
| `make test-concurrency` | `RUN=...`; optionally `AT=<ISO-instant>` |
| `make test-compare` | `BASE=<older-id> RUN=<newer-id> BY=method` |

`N=1`, `BY=module`, `TOP=20` are defaults. Compare defaults to the newest two
runs. Names and module filters are literal substrings, not shell expressions or
regular expressions. `MIN` is milliseconds. `TOP` limits output, not analysis;
inspect `matched`, `totalGroups`, `totalIntervals`, and `testsAtTimeCount` before
claiming that a returned list is exhaustive.

Summary includes per-run counts, mean/p95/max durations and mixed outcomes.
Compare reports mean-duration differences with both sample counts; a group
missing from one run was not observed there, which does not prove deletion.

## Interpret results

- Preserve module-qualified class/method identities and cite selected run IDs.
- `inputTruncated` and `sources` describe the actual byte ranges read. Partial
  input supports only partial conclusions.
- Recorded executions can include deliberately failing nested test fixtures.
  Use the build exit status and Surefire/Failsafe reports for the build verdict.
- `runCompletionKnown` is false: files contain flushed test plans, not a durable
  whole-build completion marker. An absent test may still be running or unflushed.
- `wallMillis` spans observed test intervals, not the complete Maven invocation.
  Summed test durations can exceed wall time because tests overlap.
- Concurrency uses half-open intervals `[start, end)` and excludes skipped tests.
  `busiestIntervals` is a bounded selection, not a complete chronological trace.
- Known process/thread peaks use recorded identities. Old records lack them;
  zero known processes does not mean no JVM ran.
- Overlap is not CPU utilization, configured worker count, or proof of resource
  contention. Use existing JFR reports when CPU/allocation evidence is needed.
- Mixed successful/failed outcomes identify candidates for investigation; they
  do not by themselves prove flakiness or the cause of a slowdown.

Report observations and uncertainties separately. Do not start another test run
merely to inspect existing data.

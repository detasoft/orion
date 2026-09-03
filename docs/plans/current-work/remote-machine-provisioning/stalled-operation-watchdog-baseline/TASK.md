# Fix the Stalled SSH Operation Watchdog Baseline

Status: todo
Owner: codex, session watchdog-baseline-7c2a, started 2026-09-04 00:17 Europe/Amsterdam.

Restore the full-project test baseline after the focused
`MinaSshOperationTest#wholeOperationWatchdogClosesAStalledSession` scenario
consistently reports `SSH provisioning operation timed out` from
`MinaSshOperation.open`.

## Scope

- Determine why the test's expected watchdog closure escapes as an operation
  timeout instead of the asserted outcome.
- Fix the root cause without weakening the whole-operation deadline or hiding
  genuine provisioning timeouts.
- Verify the focused test repeatedly and restore a passing `make test`
  baseline.

# Fix the Stalled SSH Operation Watchdog Baseline

Status: complete

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

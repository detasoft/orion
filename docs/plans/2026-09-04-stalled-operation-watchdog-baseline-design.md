# Stalled SSH Operation Watchdog Baseline Design

## Context

`MinaSshOperationTest#wholeOperationWatchdogClosesAStalledSession` gives the whole SSH operation a
100 ms deadline. The production watchdog starts before connection and authentication, as required by
the whole-operation contract. Under ordinary scheduling variation, the deadline can therefore close
the client during `MinaSshOperation.open` before the test reaches its stalled command assertion.

## Design

Keep the production watchdog and timeout classification unchanged. Give this real-SSH test a two-second
whole-operation deadline while retaining a five-second remote sleep. This leaves enough time for setup
but still forces the watchdog, rather than the command timeout, to terminate the stalled operation.

Adding a test-controlled scheduler would make the timing fully synthetic but would expand the production
test seam for one integration-style scenario. Starting the watchdog after authentication would invalidate
the whole-operation deadline and is not acceptable.

## Error Handling and Verification

The test will continue to require a `ProvisioningException` classified as `ProvisioningFailure.TIMEOUT`.
Run the focused test repeatedly to check the baseline under repeated SSH setup, then run `make test` for
the complete project.

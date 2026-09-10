# Bootstrap Error Cause Visibility Design

## Problem

`BootstrapContext.open` replaces every bootstrap failure with a generic
`IllegalStateException`. The application log therefore omits the concrete safe
diagnostic produced by the failing bootstrap component.

## Design

Keep `Bootstrap inputs are unavailable or invalid` as the public top-level
message and preserve the caught failure as the exception cause. Existing
callers and tests that depend on the generic message remain valid, while the
application stack trace includes the concrete nested failure.

Do not add logging inside `BootstrapContext`: `App` remains the single logging
boundary. Existing bootstrap components remain responsible for ensuring their
exception messages do not disclose secrets or sensitive paths.

## Verification

Extend `BootstrapContextTest` to assert that a representative missing bootstrap
input is retained as the cause with its concrete message. Run the focused
bootstrap test, then the routine development verification.

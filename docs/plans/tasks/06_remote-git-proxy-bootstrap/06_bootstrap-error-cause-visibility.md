# Verify Bootstrap Diagnostic Cause Safety

## Current evidence

The implementation requested by the former leaf already exists in `9b0bb402`:
`BootstrapContext.open` retains the generic top-level message and caught cause.
`BootstrapContextTest.preservesSpecificKeyMaterialFailureAsCause` checks the
specific nested diagnostic. No second cause-preservation implementation is needed.
This leaf retains the remaining verification boundary; implementation presence
alone does not establish full acceptance.

## Dependencies

The integrated bootstrap and diagnostic test foundations are available. This
verification does not depend on persistent adoption, admin UI, or mirror workers.

## Requirements and design

Keep `App` as the single logging boundary and preserve safe nested diagnostics.
Every retained cause and suppressed exception must obey the existing no-secret,
no-sensitive-path requirement; generic top-level text alone is insufficient.

## Implementation plan

1. Inspect exception chains from representative failed configuration, material,
   credential-file, and upstream operations, including logged nested causes.
2. Reuse or extend behavioral coverage only where safe-cause/redaction coverage
   is absent. Fix any task-local leak at the component that owns the diagnostic.
3. Verify existing cause assertions and negative secret/path cases; retain
   concrete safe failure information and avoid duplicate logging.

## Acceptance

The generic top-level message and representative safe cause remain observable;
secret values and protected paths are absent from the complete rendered failure.
Run focused bootstrap/redaction tests and the required JVM verification for any
implementation changes before completing this leaf.

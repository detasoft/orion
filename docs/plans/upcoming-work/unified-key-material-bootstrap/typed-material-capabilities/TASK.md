# Introduce Typed Material Capabilities

Status: todo
Depends on: ../contracts-and-bootstrap-order/TASK.md

Replace unrestricted string-alias access at runtime with typed, purpose-scoped
capabilities while retaining one internal keystore owner.

## Scope

- Model alias, purpose, algorithm, version, and cluster or node scope.
- Validate existing entries before reusing or activating them.
- Expose narrow signing, verification, TLS, SSH, CA, and configuration cipher
  interfaces instead of injecting the raw material service into consumers.
- Keep private and symmetric keys inside the narrowest practical boundary.
- Reject cross-purpose alias reuse and unsafe rotation targets.

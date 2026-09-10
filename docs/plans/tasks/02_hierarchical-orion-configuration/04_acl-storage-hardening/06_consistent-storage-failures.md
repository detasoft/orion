# Unify Storage Failure Reporting

Status: todo

Use one explicit failure contract for ACL storage reads and writes instead of
combining typed read results with backend-specific unchecked save exceptions.

## Scope

- Define the small set of failures callers need to distinguish, including
  missing bootstrap state, invalid configuration or snapshot, unavailable
  storage, version conflict if supported, and unexpected failure.
- Return or otherwise expose expected save failures through the same typed
  control-flow model used by load; do not treat arbitrary runtime exceptions as
  backend failures.
- Translate filesystem and native Git failures consistently at the connector
  boundary while retaining their causes for diagnostics.
- Migrate ACL service consumers so bootstrap creation, retry decisions, and
  user-facing errors depend on the shared failure kind rather than a concrete
  backend exception.

## Completion Criteria

- Focused contract tests run the same missing, invalid, unavailable, and save
  failure scenarios against every supported backend where the scenario
  applies.
- `save` cannot report apparent success and later escape through an unrelated
  unchecked exception type for an expected storage failure.
- `NOT_FOUND` remains distinct so missing bootstrap configuration is not
  confused with an inaccessible or corrupt store.

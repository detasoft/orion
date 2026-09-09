# Restore Local Storage Characterization

Status: todo

Restore focused tests for the supported Local ACL storage behavior that was
lost when the old monolithic storage/service suite was deleted.

## Scope

- Recover the still-valid scenarios from the deleted
  `AccessControlStorageTest` without restoring its ACL service, authentication,
  or obsolete backend coupling.
- Cover loading an existing configured file, reporting a missing file,
  creating parent directories on initial save, overwriting content, handling
  multiple configured files, and returning the configured primary path.
- Cover resolver selection for currently supported filesystem and native Git
  locations without tests whose sole purpose is to assert that a removed
  backend remains absent.
- Keep behavior changes for exact snapshot keys, symlink containment, and the
  new error model in their respective tasks; this task records the valid
  baseline those changes build upon.

## Completion Criteria

- Local storage has a small dedicated test class with happy-path and meaningful
  missing/overwrite/multi-file scenarios.
- Tests exercise the connector directly and do not require the full ACL
  service or application runtime.
- Removed remote and placeholder storage behavior is not reintroduced as test
  fixtures or compatibility assertions.

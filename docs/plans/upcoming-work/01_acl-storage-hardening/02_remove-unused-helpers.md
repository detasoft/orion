# Remove Unused ACL Storage Helpers

Status: todo

Delete helper code and fixtures that no longer support a live ACL storage
path, without removing similarly named test accessors owned by other modules.

## Scope

- Delete `AccessControlStorageSecret` after the unsupported remote ACL storage
  implementation is removed and no caller remains.
- Delete the unused `PlainRootTokenAccessForTests` copy from the ACL storage
  test sources; preserve the independently used copies in their owning test
  modules.
- Remove imports, dependencies, fixtures, and package exposure made unused by
  those deletions.
- Confirm there are no generated, reflective, service-loader, or test-jar
  consumers before deleting each helper.

## Completion Criteria

- Production and test searches show no orphaned helper references in the ACL
  storage module.
- The module compiles and its focused tests pass without replacement helpers.

# Use Canonical Repository Names

Status: todo

Define one storage-neutral contract for parsing, normalizing, and validating
repository names, then use it at every repository ingress instead of retaining
connector- and transport-specific string checks.

## Scope

- Inventory repository-name parsing in wire bootstrap, configuration,
  authorization, ACL bootstrap, and native repository providers.
- Put the canonical value/parser below both transport parsing and storage so
  `git-parser`, native storage, and connectors can consume it; do not add an
  `acl-storage` dependency on `git-parser` or make `git-parser` the owner of the
  repository identity contract.
- Coordinate the accepted canonical form with the planned `org/team/repo`
  address, without mixing transport decorations such as a leading slash or
  `.git` suffix into the stored identity.
- Normalize separators before validation and reject empty names, NUL,
  absolute paths, and `.` or `..` path segments consistently, including
  percent-decoded backslashes.
- Replace `NativeGitAccessControlStorage.repositoryName`,
  `GitWireBootstrap.normalizeRepositoryPath`, and provider-local name checks
  with the shared contract.

## Completion Criteria

- Equivalent names normalize identically at all supported ingress points, and
  invalid names are rejected before a repository provider is called.
- Tests cover slash and backslash separators, encoded input, dot segments,
  leading transport separators, optional `.git`, and canonical qualified
  names.
- Maven dependency checks confirm that ACL storage does not depend on
  `git-parser` and the shared contract does not depend on a transport module.

## Coordination

- [Parser/storage boundary](../../git-wire-architecture-simplification/parser-storage-boundary/TASK.md)
- [Hierarchy and identifiers](../../../current-work/hierarchical-orion-configuration/hierarchy-and-identifiers/TASK.md)

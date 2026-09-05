# Model Organization-Local Users

Status: todo
Owner: codex, session organization-users-4c81, started 2026-09-05 18:26 Europe/Amsterdam.

Move ordinary user ownership into organizations while retaining a separate
system administration and recovery scope.

## Scope

- Scope user ids, credentials, memberships, and role references to one
  organization.
- Define qualified login and principal identity when user ids repeat across
  organizations.
- Store public keys and password verifiers directly; represent confidential
  credentials only as validated encrypted values.
- Prevent teams and repositories from referencing users in another organization.
- Define disabled users, credential rotation, and legacy user migration.

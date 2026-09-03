# Add Scoped Roles and Grants

Status: todo

Allow organizations, teams, and repositories to define local roles and grants
without relying on one global ACL namespace.

## Scope

- Define role identity and references relative to their owning scope.
- Model organization-wide, team-local, and repository-local permissions.
- Define inheritance, explicit override or deny behavior, and invalid cycles.
- Keep membership separate from permission definitions.
- Validate duplicate, missing, escaping, and cross-scope references.

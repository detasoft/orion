# Implement Hierarchical Authorization

Status: todo
Depends on: ../organization-users/TASK.md,
../scoped-roles-and-grants/TASK.md

Evaluate access against the organization, team, and repository path using one
immutable configuration snapshot.

## Scope

- Resolve qualified principals, memberships, local roles, and inherited grants.
- Apply deterministic allow and deny precedence at each scope.
- Preserve separate system-administrator and bootstrap-recovery authority.
- Update repository, branch, administration, and future secret actions to use
  the hierarchical resource identity.
- Test same-name users, cross-organization isolation, inheritance, overrides,
  disabled users, and repository-local roles.

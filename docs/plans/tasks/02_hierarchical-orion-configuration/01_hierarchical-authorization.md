# Implement Hierarchical Authorization

Status: todo
Depends on: completed organization-users (748088a4),
completed scoped-roles-and-grants (748088a4)

Evaluate access against the organization, team, and repository path using one
immutable configuration snapshot.

## Scope

- Resolve qualified principals, memberships, local roles, and inherited grants.
- Apply deterministic allow and deny precedence at each scope.
- Preserve separate system-administrator and bootstrap-recovery authority.
- Update repository, branch, administration, and future secret actions to use
  the hierarchical resource identity.
- Replace the permissive `DecisionRegistry` authorization stub in `OrionRuntimeModule`
  with current scoped access checks for listing, viewing, and resolving pending
  decisions; cover revoked access and isolation between scopes.
- Test same-name users, cross-organization isolation, inheritance, overrides,
  disabled users, and repository-local roles.

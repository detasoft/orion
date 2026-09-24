# Implement Hierarchical Authorization

Status: todo
- Owner: codex, session 01a0d010-05d8-7b91-a154-45200daf87f0, branch `codex/hierarchical-authorization-01a0d010`,
  worktree `.worktrees/hierarchical-authorization-01a0d010`, started 2026-09-24 09:59 Europe/Amsterdam.
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

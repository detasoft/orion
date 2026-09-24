# Implement Hierarchical Authorization

Status: todo
- Owner: codex, session 01a0d010-05d8-7b91-a154-45200daf87f0, branch `codex/hierarchical-authorization-01a0d010`,
  worktree `.worktrees/hierarchical-authorization-01a0d010`, paused 2026-09-24 13:27 Europe/Amsterdam;
  next: restore GitHub Packages authentication for rust-maven-plugin:0.1.0, rerun make test on main,
  then remove the task worktree and branch. Reviewed commit 6c2ff298 was integrated as 1f09d248.
Depends on: completed organization-users (748088a4),
completed scoped-roles-and-grants (748088a4)

Evaluate access against the organization, team, and repository path using one
immutable configuration snapshot.

## Scope

- Resolve qualified principals, local roles, direct grants, and inherited grants.
- Apply deterministic allow and deny precedence at each scope.
- Preserve separate system-administrator and bootstrap-recovery authority.
- Update repository, branch, and administration checks to use hierarchical
  resource identity. Retain the generic scope evaluator for future secret
  consumers; secret action policy belongs to `05_secret-reference-credential-management.md`.
- Preserve the current `DecisionRegistry` scoped authorization implemented in
  85e95f80 for listing, viewing, and resolving pending decisions; cover revoked
  access and isolation between scopes.
- Test same-name users, cross-organization isolation, inheritance, overrides,
  deleted users, revoked roles, and repository-local roles.

## Design

- Keep the shared `AccessControl.User` model introduced in 09c53305. The user
  confirmed on 2026-09-24 that enabled state and team memberships must not be
  restored; verify removal and role revocation instead.
- Reuse `ScopedAccess` and the authoritative immutable `OrionDesiredState`
  document. Each authorization decision reads one current snapshot; a branch
  decision reuses that snapshot for its repository and branch checks. An
  already authenticated identity observes user removal and role revocation on
  its next check.
- Preserve existing matching-DENY precedence over ALLOW, independent of role
  assignment order. Local role assignments do not grant access to ancestors
  or sibling scopes. Scope limits ownership; expressions select operations
  and may further restrict the resource.
- Preserve system ACL matching, legacy repository names, system administration,
  and root recovery. Organization repository checks use the qualified
  organization/team/repository address.
- Preserve existing branch restriction combinations. Branch-specific denies
  apply to matching branches without denying unrelated branches. Repository
  creation may evaluate ancestor grants for an absent repository in an
  existing configured team.

## Implementation plan

1. Trace authenticated identities, snapshot ownership, repository and branch
   checks, and administration consumers; retain one canonical scoped evaluator.
2. Connect organization repository and branch authorization to the current
   immutable configuration, including direct and assigned inherited grants.
3. Preserve live decision administration checks and separate system authority.
4. Add behavioral coverage and verify focused authorization, ACL, and bootstrap
   tests, followed by the full test suite.

## Acceptance

- Same-name users in different organizations remain isolated; missing users and
  foreign scopes fail closed, including after configuration changes.
- Ancestor grants, repository-local roles, and local roles importing ancestor
  grants work without broadening assignment scope; unassigned denies have no
  effect and matching assigned denies win regardless of ordering.
- Repository create/read/write/force and branch fetch/push honor scoped roles,
  direct grants, and branch restrictions; read-only grants cannot expand push
  permission. Creation works for an absent repository under an existing team.
- A branch decision uses one configuration snapshot. User removal and role
  revocation affect subsequent checks and pending-decision access.
- Existing system ACL and bootstrap-recovery behavior remains covered.

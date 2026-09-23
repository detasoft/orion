# Implement Hierarchical Authorization

- Owner: codex, session 01a0cd57-6e6e-7083-bffc-2f8231e97bc9, branch `codex/hierarchical-auth-01a0cd57`,
  worktree `.worktrees/hierarchical-auth-01a0cd57`, started 2026-09-23 10:27 Europe/Amsterdam.

Depends on: completed organization-users (748088a4),
completed scoped-roles-and-grants (748088a4)

## Required result

Connect the existing organizational user/role/grant model to production
HTTP/API/SSH authentication and authorization. A qualified organization user
can authenticate with existing credentials and exercise exactly the applicable
rights in that organization. Same-name users in different organizations remain
isolated. Disabled/deleted users and revoked rights cease granting access.
This is the prerequisite for shared Google/corporate OIDC invitations, not an
OIDC-specific permission system.

## Current model and preserved behavior

OrganizationUser, PrincipalAddress, ConfigurationScope, ScopedRole, ScopedGrant,
role references, memberships, and qualified repository addresses already exist.
Runtime ACL authentication and GrantAccess currently use only flat system ACL
users and grants. OrionDesiredState publishes the immutable OrionDocument;
existing persistence supports revision-checked atomic configuration updates.

Preserve existing system user credentials, Basic/Bearer/SSH protocols, root
bootstrap/recovery/generation checks, existing system ACL behavior and XML
contracts. System administrators remain distinct from organization admins.
Do not duplicate organization users or grants into persisted system ACL records.
No new database, external policy service, feature flag, or OIDC-only evaluator.

## Design and semantics

- Use existing PrincipalAddress as canonical principal identity. Organization
  principals are organization/user; system principal identities retain required
  existing wire/token compatibility through one explicit boundary mapping.
  Never resolve a qualified organizational identity as a same-name system user.
  Unqualified existing login names continue identifying existing system users.
- Use one immutable current document snapshot per access evaluation. Resolve
  user enabled state, team memberships, assigned roles and referenced grants
  within it. Authorization must not rely indefinitely on grants captured at
  login; check current policy for long-lived authenticated requests/operations.
- Resolve only explicitly assigned roles and their transitive references.
  Definitions of roles/grants alone do not grant them to every organization
  member. Team membership bounds team-scoped role applicability; membership
  alone does not grant administrative access. Reject or deny out-of-scope
  references and cross-organization access.
- A grant applies only within its owning scope and descendants, further
  constrained by its expressions and the requested action/resource. Ancestor
  permissions inherit downwards, not across sibling organizations/teams/repos.
  Resolve references with their owning scopes so referencing a narrow grant
  cannot broaden it. Role ownership also bounds its applicable scope.
- Default deny. A matching explicit DENY wins over every applicable ALLOW,
  including a more local ALLOW; a local DENY restricts an inherited ALLOW.
  A branch-specific deny must not hide unrelated branches, and an ALLOW must
  not bypass branch constraints. Preserve existing system ACL branch semantics.
- Organization grants cannot confer system-level administrator/shutdown/root
  recovery capabilities. Scope-aware administrative decisions use the same
  evaluator and current principal; global administrative routes remain global.
- Update every real identity/resource/rule consumer to the canonical path.
  Remove replaced internal flat-only methods/logic rather than retain aliases
  or compatibility wrappers. System ACL policy remains supported as existing
  persisted/wire behavior via the same evaluation boundary.
- Replace DecisionRegistry's permissive (actor, scope) -> true wiring in
  OrionRuntimeModule. Listing, viewing and resolving use current scoped policy;
  update HTTP/SSH decision callers to preserve actual principal scope. Ensure
  a revoked user cannot approve a pending decision and a user from another
  organization cannot observe or resolve it.

## Implementation plan

1. Trace OrionAccessControlServiceImpl publication/authentication/token/SSH
   paths, UserIdentity/InternalUserImpl/SecurityContext, GrantAccess and all
   access rules/resources, runtime wiring, command/domain views, and decision
   registry boundaries. Read local AiRule comments and existing policy tests.
2. Implement canonical principal lookup and a minimal snapshot-based evaluator
   using current schema types. Update all real consumers and existing tests of
   replaced APIs. Preserve one authoritative document and no persisted mirrors.
3. Authenticate enabled organization users through existing password/public-key
   methods; issue/verify/refresh tokens with unambiguous principal mapping.
   Revalidate disabled/deleted principals, key ambiguity, and root isolation.
4. Connect repository, branch, connection, scoped administration and decisions
   to the shared evaluator, including HTTP/SSH paths and existing live-session
   resource boundaries. Preserve system behavior and avoid widening rights.
5. Add meaningful behavior coverage for successful organization login/access,
   same-name principal isolation, disabled users, grant revocation after login,
   transitive roles, team membership, scope inheritance, local deny/inherited
   allow, deny precedence, branch restrictions, repository-local roles, and
   root/system compatibility. Include real transport/decision boundary tests.
6. Document qualified login and applicable grant/deny rules in existing docs.
   Run focused make run-test checks during development and full make test.

## Acceptance

A configured organization user can authenticate over supported existing
transports, obtain a Bearer token, and access only explicitly allowed resources.
The same shared policy applies to HTTP/API and SSH operations. Policy changes
and disabled users affect subsequent access. Organization permissions cannot
escape their scope or grant global administration. Pending decisions respect
current principal and resource scope. Existing system admin/recovery and system
ACL behavior remain covered. No production OIDC changes belong in this task.

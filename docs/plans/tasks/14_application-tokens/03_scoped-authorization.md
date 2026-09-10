# Application Token Scoped Authorization

## Goal

Enforce application token scopes as a restriction on the authenticated user's
existing ACL grants.

Application tokens should never grant permissions by themselves. A request made
with an application token is allowed only when both conditions are true:

1. the owning ACL user has the required permission;
2. the token scope set allows the requested action.

This creates least-privilege automation tokens without replacing the ACL model.

## Current State

- `2026-05-14-application-token-model-storage.md` persists token `scopes`, but
  explicitly leaves enforcement to a later phase.
- `2026-05-17-application-token-admin-api-usage.md` validates and stores scopes,
  but does not apply fine-grained authorization decisions.
- `2026-05-14-oauth-application-token-authentication.md` says scoped token
  authorization should prefer the intersection of user grants and token scopes.
- `2026-05-15-git-access-policy-protected-refs.md` needs application token and
  future OAuth scopes to map into Git policy decisions.

## Non-Goals

- Do not replace ACL users, roles, grants, or repository policies.
- Do not implement OAuth/OIDC login flows in this step.
- Do not add provider-specific OAuth scopes in this step.
- Do not make token scopes more permissive than the owning user's grants.
- Do not require Git protocol code to inspect raw token records directly.
- Do not expose raw token secrets or verifier hashes in authorization decisions,
  logs, or audit records.

## Scope Semantics

Scopes are named capabilities stored on the application token record. They are
not ACL grant expressions and they do not name users, roles, or passwords.

Use a registry of known scope definitions:

- stable scope name;
- human-readable description;
- action category;
- implied child scopes, if any;
- whether the scope is allowed for self-service token creation;
- whether the scope requires administrator creation;
- whether the scope is sensitive and should be highlighted in admin APIs.

Unknown scopes should be rejected when creating or rotating a token after this
feature is enabled. Existing records with unknown scopes should fail closed for
actions that require those scopes and should be reported by validation tooling.

## Effective Permission Model

Add token-aware authorization context:

```text
AuthenticatedSubject
  userId
  authKind: JWT | APPLICATION_TOKEN | FUTURE_OAUTH
  tokenId?
  tokenScopes?
  tokenOrigin?
```

Authorization flow:

1. Authenticate the request.
2. Resolve the ACL user from the current ACL snapshot.
3. Resolve the requested action and resource.
4. Check ACL grants for the user.
5. If the request uses an application token, check token scopes.
6. Allow only when both checks allow.
7. Record a policy decision with safe diagnostics.

Requests authenticated by existing short-lived JWTs continue to use ACL-only
authorization unless the JWT carries a restrictive token context in a later
phase.

## Initial Scope Set

Start with a small stable set that maps to existing and planned authorization
surfaces:

- `api:admin:read`: read admin metadata where the user already has ACL access.
- `api:admin:write`: write admin metadata where the user already has ACL access.
- `token:self`: manage the caller's own application tokens.
- `repository:read`: read repository metadata and files.
- `repository:write`: write repository files through supported APIs.
- `git:fetch`: use Git upload-pack/read operations.
- `git:push`: use Git receive-pack/write operations.
- `git:ref:create`: create refs when repository policy allows it.
- `git:ref:delete`: delete refs when repository policy allows it.
- `git:ref:force`: force-update refs when repository policy allows it.
- `git:tag:create`: create tags when repository policy allows it.
- `git:tag:update`: update tags when repository policy allows it.
- `mirror:read`: inspect mirror configuration and status.
- `mirror:write`: run or update mirror operations.
- `maintenance:read`: inspect maintenance state and reports.
- `maintenance:run`: run non-destructive maintenance tasks.
- `maintenance:approve`: approve destructive maintenance actions.

Wildcard scopes can be added later only if the registry can expand them into
known concrete scopes before persistence. The first implementation should avoid
runtime wildcard matching.

## Scope Implications

Keep implications explicit and conservative:

- `repository:write` implies `repository:read` only for repository API routes,
  not Git protocol operations.
- `git:push` implies `git:fetch` only when receive-pack needs to read base
  objects for the same repository operation.
- `api:admin:write` implies `api:admin:read` for the same admin resource family.
- `maintenance:approve` implies `maintenance:read`, but not `maintenance:run`.

Sensitive scopes should not be granted through self-service by default:

- `git:ref:force`;
- `git:ref:delete`;
- `mirror:write`;
- `maintenance:approve`;
- `api:admin:write` outside self-owned resources.

## Legacy and Migration Behavior

Because scopes may already be persisted before enforcement, add an explicit
compatibility mode:

- `DISABLED`: store scopes but do not enforce them.
- `AUDIT_ONLY`: compute scope decisions and log/audit would-deny decisions.
- `ENFORCE_NEW_TOKENS`: enforce scopes for tokens created after a configured
  timestamp or schema marker.
- `ENFORCE_ALL`: enforce scopes for every application token.

Default rollout should move through `AUDIT_ONLY` before `ENFORCE_ALL`.

For unscoped tokens:

- in `DISABLED`, preserve existing behavior;
- in `AUDIT_ONLY`, record would-deny decisions for scoped actions;
- in `ENFORCE_NEW_TOKENS`, allow legacy tokens only if they predate enforcement;
- in `ENFORCE_ALL`, deny actions that require scopes.

Admin APIs should expose enough metadata for operators to find legacy unscoped
tokens and rotate them into scoped replacements.

## HTTP API Integration

Each HTTP route should declare a required logical action. The authorization layer
maps that action to both ACL checks and optional token scope checks.

Examples:

- list own application tokens: ACL self-management plus `token:self`;
- create token for another user: administrator ACL action plus
  `api:admin:write`;
- read repository files: repository ACL read plus `repository:read`;
- write repository files: repository ACL write plus `repository:write`;
- inspect maintenance reports: maintenance ACL read plus `maintenance:read`;
- run dry-run maintenance: maintenance ACL run plus `maintenance:run`.

The route handler should not inspect scope strings directly. It should ask the
authorization service for an allow/deny decision.

## Git Policy Integration

Git protocol code should continue to call the Git policy service, not token
storage.

Map token scopes into Git policy:

- ref advertisement requires ACL visibility and `git:fetch`;
- upload-pack wants require ACL read and `git:fetch`;
- receive-pack requires ACL write and `git:push`;
- branch creation additionally requires `git:ref:create`;
- tag creation additionally requires `git:tag:create`;
- tag update additionally requires `git:tag:update`;
- ref deletion additionally requires `git:ref:delete`;
- non-fast-forward update additionally requires `git:ref:force`;
- mirror actor operations additionally require `mirror:write` when backed by an
  application token.

Protected ref policy remains authoritative. A token with `git:ref:force` cannot
force-push if protected ref configuration forbids force-push for the user.

## Future OAuth Integration

Future OAuth/OIDC login can reuse the same authorization context shape, but OAuth
provider scopes must not be treated as Orion permission scopes automatically.

An OAuth-authenticated session may later receive Orion scopes from:

- explicit administrator mapping;
- a linked Orion user policy;
- a short-lived Orion JWT minted with a restrictive scope set.

Provider scopes such as `email` or `profile` are identity-provider permissions,
not Orion resource permissions.

## Failure Behavior

Authorization failures should distinguish internal reasons while exposing safe
external messages:

- ACL denied;
- token scope missing;
- token scope unknown;
- token scope disabled by policy;
- sensitive scope requires administrator-created token;
- legacy unscoped token denied by enforcement mode.

External responses should not reveal hidden repositories or hidden refs. Git
protocol errors should preserve existing sanitized not-found behavior where
repository existence must be hidden.

## Audit and Diagnostics

Policy decision records should include:

- actor user id;
- auth kind;
- token id when present;
- requested action;
- resource type and sanitized resource id;
- ACL decision;
- token scope decision;
- missing scope names when safe;
- enforcement mode;
- final decision.

Do not log raw token values, verifier hashes, Authorization headers, file
contents, or hidden ref names.

## Configuration

Add configuration for:

- scope enforcement mode;
- enforcement start timestamp or schema marker;
- allowed self-service scopes;
- sensitive scopes requiring administrator creation;
- whether unknown scopes fail startup or only fail validation tooling;
- audit-only sampling or rate limiting for high-volume Git fetch paths.

Configuration should support a dry-run rollout where operators can inspect
would-deny decisions before enforcing scopes.

## Implementation Plan

### Phase 1: Scope Registry

- Add scope definition model and registry.
- Define initial scope names, descriptions, sensitivity flags, and implications.
- Validate application token records against the registry.
- Test unknown scopes, implications, and sensitive-scope flags.

### Phase 2: Authenticated Subject Context

- Extend authentication result to carry auth kind, token id, token origin, and
  token scopes.
- Keep existing JWT authentication behavior unchanged.
- Test application-token authentication includes scope context and JWT
  authentication remains ACL-only.

### Phase 3: Authorization Intersection

- Add token-scope checks to the authorization service after ACL checks.
- Return structured allow/deny reasons.
- Test user-grant allowed plus token-scope denied, user-grant denied plus
  token-scope allowed, and both allowed.

### Phase 4: Enforcement Modes

- Implement `DISABLED`, `AUDIT_ONLY`, `ENFORCE_NEW_TOKENS`, and `ENFORCE_ALL`.
- Add legacy unscoped-token behavior.
- Test mode transitions and would-deny audit records.

### Phase 5: HTTP Route Mapping

- Map admin, token, repository, and maintenance routes to logical actions.
- Replace direct route-level scope checks with authorization service calls.
- Test self-service token management, admin token management, repository read,
  repository write, and maintenance actions.

### Phase 6: Git Policy Mapping

- Feed token scope context into Git policy requests.
- Enforce `git:fetch`, `git:push`, ref create/delete/force, tag create/update,
  and mirror-write scopes.
- Test upload-pack, receive-pack, hidden refs, protected refs, and sanitized
  Git protocol failures.

### Phase 7: Admin API Integration

- Validate requested scopes during create and rotate.
- Reject self-service creation of sensitive scopes unless policy allows it.
- Expose derived warnings for legacy unscoped tokens.
- Test scope validation and self-service restrictions.

### Phase 8: Audit and Observability

- Emit structured policy decision records and metrics.
- Add redaction tests for token values, Authorization headers, and hidden refs.
- Add metrics for scope-denied decisions by action category.

## Verification Plan

- Scope registry tests for known scopes, unknown scopes, implications, and
  sensitive flags.
- Authentication tests proving application-token scope context is populated.
- Backward compatibility tests proving JWT requests keep existing ACL-only
  behavior.
- Authorization tests proving final permission is ACL grants intersected with
  token scopes.
- Enforcement mode tests for disabled, audit-only, new-token-only, and all-token
  enforcement.
- HTTP route tests for token self-management, admin token management, repository
  read/write, and maintenance actions.
- Git policy tests for fetch, push, create ref, delete ref, force update, tag
  operations, protected refs, and hidden refs.
- Admin API tests for invalid scopes and sensitive self-service scopes.
- Audit/redaction tests proving token secrets and hidden refs are not logged.

## Acceptance Criteria

- Application token scopes restrict but never expand the owning user's ACL
  permissions.
- Operators can roll out enforcement through audit-only and enforcement modes.
- HTTP and Git authorization use the same token-aware authorization context.
- Git protected-ref policy remains authoritative after token scopes are added.
- Existing JWT authentication remains compatible.
- Unknown or missing scopes fail closed when enforcement is enabled.
- Audit and metrics explain scope decisions without exposing secrets.

## Open Questions

- Should `repository:write` imply `git:push`, or should API writes and Git pushes
  stay separate forever?
- Should unscoped legacy tokens be automatically marked for forced rotation when
  enforcement is enabled?
- Should scope definitions live in code only, or allow deployment-specific custom
  scopes later?
- Should audit-only decisions be sampled for high-volume fetch traffic?
- Should future OAuth-scoped JWTs use the exact same scope names as application
  tokens?

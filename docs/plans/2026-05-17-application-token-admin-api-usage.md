# Application Token Admin API and Usage Accounting

## Goal

Add administrative HTTP APIs and usage-accounting behavior for persisted
application tokens.

The model/storage plan defines token records, verifier hashes, rotation, and
reload behavior. This plan turns that model into operator-facing endpoints for
creating, listing, revoking, and rotating tokens while keeping raw token secrets
one-time only and keeping last-used updates safe under regular request traffic.

## Current State

- `2026-05-14-application-token-model-storage.md` defines application token
  records and storage, but leaves admin routes as a follow-up.
- `2026-05-14-oauth-application-token-authentication.md` names admin token APIs
  as a phase, but does not define endpoint behavior, authorization, redaction,
  auditing, or last-used update policy.
- `OrionAuthorizationFilter` authenticates Bearer tokens and delegates token
  verification to the access-control service.
- Existing short-lived JWT issuance must keep working while application token
  administration is added.

## Non-Goals

- Do not implement OAuth/OIDC login flows in this step.
- Do not redesign ACL grants or user storage.
- Do not require application tokens to exchange for JWTs before use.
- Do not enforce fine-grained token scopes beyond validation and persistence.
  Scope enforcement is covered by
  `docs/plans/2026-05-17-application-token-scoped-authorization.md`.
- Do not expose raw token secrets after create or rotate responses.
- Do not add a database requirement.
- Do not add Git protocol-specific token policy in this step.

## API Surface

Add HTTP admin endpoints:

```text
POST /api/admin/application-tokens
GET  /api/admin/application-tokens
GET  /api/admin/application-tokens/{id}
POST /api/admin/application-tokens/{id}/revoke
POST /api/admin/application-tokens/{id}/rotate
```

Optional follow-up endpoints can be added later:

```text
GET  /api/admin/users/{userId}/application-tokens
POST /api/admin/application-tokens/{id}/rename
POST /api/admin/application-tokens/{id}/extend
```

The first implementation can expose user filtering through query parameters on
the list endpoint instead of adding user-scoped routes immediately.

## Authorization

Define explicit admin actions before implementing routes:

- create token for self;
- create token for another user;
- list own tokens;
- list all tokens;
- inspect token metadata;
- revoke own token;
- revoke any token;
- rotate own token;
- rotate any token.

Default policy should be conservative:

- a user may list, create, revoke, and rotate their own tokens only when granted
  an application-token self-management permission;
- creating or rotating tokens for another user requires an administrator
  permission;
- listing all tokens requires an administrator permission;
- service accounts can be denied self-service token creation by policy.

The API must resolve the current authenticated subject through the current ACL
snapshot on each request. Removed or disabled users must not be able to manage
tokens even if an old token still exists.

## Create Token

Request fields:

- `subjectUserId`, optional for self-service and required for admin-created
  tokens for another user;
- `displayName`;
- `description`, optional;
- `expiresAt`, optional subject to configured maximum lifetime;
- `scopes`, optional and persisted even before scope enforcement;
- `origin`, server-controlled as `HTTP_ADMIN`;
- idempotency key, optional.

Response fields:

- token metadata without verifier hash;
- raw token secret exactly once;
- creation timestamp;
- warning if scopes are stored but not enforced yet.

Create must:

1. authorize the operation;
2. validate subject user existence;
3. enforce display-name, description, scope, and lifetime limits;
4. generate token id and secret using a cryptographically secure RNG;
5. persist only the verifier hash;
6. audit the create event without raw secret material;
7. return the raw token only in the successful response body.

If the response fails after storage succeeds, the API must not log the raw token.
The caller may need to revoke and recreate because the secret cannot be shown
again.

## List and Inspect Tokens

List responses should include:

- token id;
- subject user id and display name if available;
- display name;
- description;
- created timestamp;
- expiration timestamp;
- revoked timestamp;
- last-used timestamp, nullable;
- rotated-from token id, nullable;
- origin;
- scopes;
- derived state: active, expired, revoked.

List responses must not include:

- raw token secret;
- verifier hash;
- verifier algorithm internals beyond a safe algorithm label;
- credential material from linked secret references.

Support filters:

- subject user id;
- active only;
- expired only;
- revoked only;
- created before/after;
- last used before/after;
- origin.

Support stable pagination and sorting by created time, last-used time, display
name, and token id.

## Revoke Token

Request fields:

- optional reason;
- optional expected token state or last known update time.

Revoke must:

1. authorize the operation;
2. treat already revoked tokens as idempotent success or typed already-revoked
   result;
3. set `revokedAt`;
4. persist atomically;
5. audit actor, target token id, subject user id, and reason;
6. never require the raw token secret.

Revocation must take effect for new authentication attempts immediately after the
storage write is visible to the token service.

## Rotate Token

Rotation creates a replacement token and revokes the old token in one logical
operation.

Request fields:

- optional new display name;
- optional new description;
- optional new expiration;
- optional new scopes;
- optional reason.

Rotate must:

1. authorize the operation;
2. validate that the old token exists and is not revoked unless policy permits
   rotating revoked tokens for recovery;
3. generate a new token id and secret;
4. copy subject, display name, description, expiration, and scopes unless
   overridden;
5. revoke the old token and persist the replacement in one atomic storage
   update;
6. audit old and new token ids without raw secret material;
7. return the new raw token exactly once.

If atomic replacement is not available in a storage backend, the backend must
expose that limitation and the service must fail before generating a secret.

## Last-Used Accounting

Application token authentication should update usage metadata without turning
every API request into a blocking storage write.

Use a rate-limited touch policy:

- update `lastUsedAt` only if the previous persisted value is older than a
  configured interval;
- keep an in-memory per-token pending timestamp map;
- flush pending touches periodically and during graceful shutdown;
- never move `lastUsedAt` backwards;
- do not fail authentication only because a last-used update failed.

Authentication must still fail for missing, expired, revoked, or invalid tokens
before recording usage.

Expose metrics for:

- successful application-token authentications;
- rejected token authentications by reason;
- last-used touches queued;
- last-used touches flushed;
- last-used touch failures.

## Idempotency and Concurrency

Create can optionally support idempotency keys for clients that retry after
network failures. If implemented, idempotency records must not replay raw secrets
after the first successful response unless the server can prove the original
response was not delivered, which HTTP usually cannot.

Concurrent operations should behave as follows:

- revoke vs authenticate: authentication after committed revoke fails;
- rotate vs authenticate old token: old token fails after committed rotation;
- rotate vs revoke: one operation wins and the loser receives a typed conflict
  or already-revoked result;
- list during mutation: list returns a consistent storage snapshot.

Use optimistic version fields or storage generation checks if the storage backend
can expose them.

## Error Responses

Return typed errors with safe messages:

- unauthorized;
- forbidden;
- subject user missing;
- token missing;
- token revoked;
- token expired;
- validation failed;
- lifetime exceeds configured maximum;
- storage unavailable;
- conflict;
- rate limit exceeded.

Errors must not reveal whether a token secret prefix was valid during
authentication. Admin metadata endpoints can reveal token id existence only to
authorized callers.

## Audit and Redaction

Audit events:

- token created;
- token listed by administrator;
- token inspected;
- token revoked;
- token rotated;
- last-used flush failure;
- rejected admin operation.

Audit records should include:

- actor user id;
- target token id;
- subject user id;
- operation;
- timestamp;
- source address when available;
- reason when supplied;
- outcome.

Audit records must not include raw token secrets, verifier hashes, Authorization
headers, or request bodies containing secrets.

## Configuration

Add configuration for:

- maximum token lifetime;
- default token lifetime;
- maximum active tokens per user;
- maximum display name and description lengths;
- allowed scope names while scope enforcement is incomplete;
- last-used update interval;
- last-used flush interval;
- self-service enabled/disabled;
- admin-created tokens for service accounts enabled/disabled;
- strict startup behavior for invalid token storage.

Configuration should be reloadable only where the existing configuration model
supports safe reloads. Lifetime and token-count limits should apply to new
tokens, not retroactively revoke existing ones unless a separate policy says so.

## Implementation Plan

### Phase 1: Admin DTOs and Validation

- Add request and response DTOs for create, list, inspect, revoke, and rotate.
- Add validation for display names, descriptions, scopes, expiration, and
  pagination.
- Add redaction tests for all DTOs and error responses.

### Phase 2: Authorization Actions

- Define application-token management actions.
- Wire self-service and admin policies through the ACL authorization layer.
- Test self, other-user, and missing-permission cases.

### Phase 3: Create and One-Time Secret Response

- Implement token create through `ApplicationTokenService`.
- Return raw token only in the create response.
- Add audit events without secret material.
- Test storage failure before and after secret generation.

### Phase 4: List and Inspect

- Implement filtered, paginated list and inspect routes.
- Add stable sorting and derived state fields.
- Test that verifier hashes and raw secrets never appear.

### Phase 5: Revoke

- Implement revoke with idempotent already-revoked behavior.
- Ensure new authentication attempts fail after revoke.
- Test concurrent revoke/authenticate behavior where the storage API can model
  ordering.

### Phase 6: Rotate

- Implement atomic old-token revoke plus new-token create.
- Return new raw token once.
- Test old token rejection, new token authentication, and conflict handling.

### Phase 7: Last-Used Touch Queue

- Add rate-limited touch policy and pending touch queue.
- Add periodic flush and graceful shutdown flush.
- Test non-blocking authentication, rate limiting, monotonic timestamps, and
  flush failure metrics.

### Phase 8: Configuration and Limits

- Add configuration defaults and validation.
- Enforce token lifetime and per-user active token limits.
- Test default lifetime, maximum lifetime, disabled self-service, and active
  token count limits.

### Phase 9: Observability and Audit

- Add metrics and audit events for all admin operations.
- Redact Authorization headers and request bodies in logs.
- Test audit contents and failure cases.

## Verification Plan

- DTO validation tests for create, list filters, revoke, rotate, and pagination.
- Authorization tests for self-service and administrator flows.
- Create tests proving raw token is returned once and only a verifier hash is
  stored.
- List and inspect tests proving secret fields never appear.
- Revoke tests proving authentication fails after committed revoke.
- Rotate tests proving old token fails and new token works.
- Conflict tests for rotate/revoke ordering.
- Last-used tests for rate limiting, queued flush, shutdown flush, and failure
  tolerance.
- Configuration tests for lifetimes, active token limits, and disabled
  self-service.
- Audit/redaction tests proving secrets and Authorization headers are absent.
- Backward compatibility test proving existing JWT Bearer authentication still
  works.

## Acceptance Criteria

- Administrators can create, list, inspect, revoke, and rotate application
  tokens through HTTP APIs.
- Authorized users can manage their own tokens when self-service is enabled.
- Raw token secrets are shown only in successful create or rotate responses.
- Revoked and rotated tokens stop authenticating.
- Last-used timestamps are updated with bounded write frequency.
- API responses, logs, metrics, and audit records never expose token secrets or
  verifier hashes.
- Existing short-lived JWT authentication remains compatible.

## Open Questions

- Should create support idempotency keys in the first implementation?
- Should self-service token creation be enabled by default?
- What is the default maximum application token lifetime?
- Should list-all-token access include last-used timestamps for privacy-sensitive
  installations?
- Should active-token-per-user limits count expired but unrevoked tokens?

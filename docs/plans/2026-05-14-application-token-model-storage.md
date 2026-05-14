# Application Token Model and Storage

## Goal

Add persisted application tokens for non-interactive clients while keeping the
existing short-lived JWT access token behavior intact.

Application tokens should be individually named, scoped, expirable, revocable,
rotatable, auditable, and reloadable after server restart. Raw token secrets
must never be stored.

## Current State

Orion currently has short-lived Bearer JWTs issued through
`OrionAccessControlService.authenticateUserAndIssueToken()` and
`OrionAccessControlService.issueTokenFor()`.

`JwtAccessTokenService` signs RS256 JWTs with issuer, subject, issued-at, and
expiration claims. Token verification is stateless and resolves the JWT subject
back to an ACL user.

HTTP authentication reads `Authorization: Bearer ...` in
`OrionAuthorizationFilter` and delegates to
`OrionAccessControlService.authenticateToken()`.

The ACL model stores users, credentials, roles, and grants. It does not store
token records, token ids, revocation state, last-used timestamps, or token
scopes.

## Non-Goals

Do not replace the existing JWT access token path in this phase.

Do not implement OAuth or OIDC provider login in this phase.

Do not add token scope enforcement until the model and storage can reliably
load, save, revoke, and rotate records.

Do not store application token secrets in plaintext, reversible encryption, ACL
XML credentials, logs, or API responses.

Do not require a database. The first storage implementation should fit Orion's
current file or repository-backed configuration style.

## Token Types

Keep a strict distinction between:

- access token: short-lived Bearer JWT used on HTTP requests;
- application token: long-lived opaque secret issued once for automation;
- application token record: persisted metadata and verifier for an application
  token;
- future refresh token: out of scope for this phase.

For the first implementation, an application token should authenticate directly
as a user. A later phase can decide whether application tokens should only be
exchangeable for short-lived JWTs.

## Token Record Model

Each persisted application token record should contain:

- `id`: stable server-generated token id;
- `subjectUserId`: ACL user id that owns the token;
- `displayName`: human-readable token name;
- `verifierHash`: hash of the secret verifier, never the raw token;
- `verifierAlgorithm`: hash algorithm and version;
- `createdAtEpochSecond`;
- `expiresAtEpochSecond`;
- `lastUsedAtEpochSecond`, nullable;
- `revokedAtEpochSecond`, nullable;
- `rotatedFromTokenId`, nullable;
- `createdByUserId`, nullable;
- `origin`: `HTTP_ADMIN`, `SSH_COMMAND`, `MIGRATION`, or similar;
- `scopes`: persisted but not enforced until the scope phase;
- `description`, nullable.

The record should expose derived state:

- active when not expired and not revoked;
- expired when current time is greater than or equal to expiration;
- revoked when `revokedAtEpochSecond` is set.

## Token Secret Format

Use an opaque token value with enough structure to find the record before
verifying the secret:

```text
otk_<token-id>_<secret>
```

`otk` means Orion token key. The token id may be a URL-safe random id or UUID.
The secret must be generated with a cryptographically secure random generator
and encoded with URL-safe base64 without padding.

The token id is not secret. The secret is secret and must be shown only once.

## Verifier Hash

Persist only a verifier hash. Prefer an existing strong password hashing
service if it fits opaque token verification. If the existing password hashing
API is too user-password-oriented, add a small token verifier service that can
use Argon2id with explicit parameters.

Verification flow:

1. Parse token prefix, token id, and secret.
2. Load token record by id.
3. Reject missing, revoked, or expired records.
4. Verify the secret against `verifierHash`.
5. Resolve `subjectUserId` to an ACL user.
6. Return an authenticated identity for that user.
7. Update `lastUsedAtEpochSecond` without blocking authentication longer than
   necessary.

## Storage

Add a dedicated `ApplicationTokenStorage` abstraction instead of embedding
application tokens into ACL XML.

Reasons:

- token lifecycle is operational state, not core ACL policy;
- frequent `lastUsedAt` updates should not rewrite ACL configuration;
- tokens need revocation and rotation without changing user definitions;
- future secret handling can evolve independently from ACL schema versions.

Initial storage can be local-file based under the Orion base directory, for
example:

```text
<baseDir>/auth/application-tokens.json
```

Use a structured format with an explicit schema version. The storage API should
hide the physical format:

- `findById(String tokenId)`;
- `listBySubject(String subjectUserId)`;
- `save(ApplicationTokenRecord record)`;
- `revoke(String tokenId, Instant revokedAt)`;
- `replaceForRotation(String tokenId, ApplicationTokenRecord replacement)`;
- `touchLastUsed(String tokenId, Instant lastUsedAt)`.

The local file implementation must write atomically through a temporary file and
rename, so interrupted writes do not corrupt all token records.

## Service Boundary

Add an `ApplicationTokenService` behind `OrionAccessControlServiceImpl`.

Responsibilities:

- create token record and return the raw token once;
- authenticate raw application token values;
- list records without secret material;
- revoke records;
- rotate records;
- update last-used metadata;
- resolve active token subjects through the current ACL snapshot.

`OrionAccessControlService.authenticateToken()` can first try JWT
authentication for backward compatibility, then try application token
authentication. Alternatively, it can detect the `otk_` prefix and route
directly to the application token service.

## Rotation

Rotation should create a new token secret and invalidate the old token in one
logical operation.

The replacement record should copy subject, display name, scopes, expiration,
and description unless the request overrides them. The old record should set
`revokedAtEpochSecond`; the new record should set `rotatedFromTokenId`.

If storage fails midway, the operation must not leave both tokens active unless
the API explicitly reports that outcome. Prefer a single storage write that
updates old and new records together.

## Reload Behavior

Application token authentication must work after server restart.

On startup, the token service should load token records lazily or during the
same lifecycle phase as ACL load. Invalid token storage should fail startup only
if configured as strict. A recovery mode may ignore invalid records and report
them through process logs, but the default should avoid silently dropping token
security state.

ACL reload should affect token authentication immediately because a token's
subject must still resolve to an ACL user. If the user is removed or disabled in
a later user-state model, the token must stop authenticating.

## Admin API Follow-Up

This plan does not need to implement admin routes, but the model should support
the next API shape:

- `POST /api/admin/application-tokens`;
- `GET /api/admin/application-tokens`;
- `GET /api/admin/application-tokens/{id}`;
- `POST /api/admin/application-tokens/{id}/revoke`;
- `POST /api/admin/application-tokens/{id}/rotate`.

Responses must never include `verifierHash` or raw token secrets except the
single successful create or rotate response.

## Open Questions

Should application tokens be valid Bearer values directly, or should clients
exchange them for short-lived JWT access tokens?

Should `lastUsedAt` be updated synchronously on every successful request,
batched, or rate-limited per token?

Should tokens have an absolute maximum lifetime enforced by configuration?

Should token storage live only in local files first, or should it immediately
support repository-backed configuration storage?

Should scopes be stored as ACL grant expressions from the beginning, even if
enforcement comes later?

How should token records behave when the subject user is renamed, removed, or
loses grants?

## Verification

Cover at least these cases:

- create returns a raw token once and persists only a verifier hash;
- raw token authenticates as the owning ACL user;
- malformed token values are rejected without storage mutation;
- unknown token id is rejected;
- wrong secret for a known token id is rejected;
- expired token is rejected;
- revoked token is rejected;
- rotation revokes the old token and authenticates the new token;
- list operations never expose raw secrets or verifier hashes;
- `lastUsedAtEpochSecond` updates after successful authentication;
- token storage reloads records after service restart;
- interrupted local file write leaves either the old valid file or the new valid
  file, not a partial file;
- existing JWT Bearer token authentication still works unchanged.

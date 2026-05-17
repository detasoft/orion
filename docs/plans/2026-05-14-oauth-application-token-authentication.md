# OAuth and Application Token Authentication

## Goal

Improve Orion authentication so human users can sign in through OAuth or OIDC
providers and automated clients can use managed application tokens with
explicit scope, lifetime, rotation, and revocation.

## Current State

Orion stores users, credentials, roles, and grants in the ACL model.

HTTP token issuance currently uses Basic credentials on `/api/admin/token` and
returns a Bearer JWT. SSH can also issue a token through `issue-token`.

`JwtAccessTokenService` issues RS256 JWTs with issuer, subject, issued-at, and
expiration claims. Verification checks signature, issuer, subject, expiration,
and that the subject still maps to an ACL user.

Bearer token authentication is stateless. There is no token id, no scope claim,
no refresh token, no revocation list, no rotation lifecycle, and no persisted
application-token record.

## Non-Goals

Do not replace the existing ACL and grant system.

Do not add OAuth provider support before Orion has a stable mapping from
external identities to local users and grants.

Do not treat long-lived application tokens as ordinary short-lived session
tokens.

Do not store raw application tokens. Store only a verifier or hash.

## Scope

Split token concepts:

- short-lived access tokens used for HTTP requests;
- application tokens intended for automation;
- external OAuth or OIDC login identities;
- optional refresh or renewal tokens, if a browser flow is added later.

Add token metadata:

- token id;
- subject user id;
- token kind;
- display name;
- created time;
- expires time;
- last used time;
- revoked time;
- allowed scopes or grant snapshot;
- optional origin such as OAuth provider or SSH issue command.

Application tokens should be persisted, individually revocable, and rotatable.
The token value should be shown once and stored as a strong hash or verifier.

OAuth or OIDC support should introduce provider configuration, callback routes,
state validation, external identity mapping, and clear behavior for first login
when no matching Orion user exists.

## Phased Plan

Phase 1: Token model and storage.

Add persisted application-token records with hashed token verifiers. Keep the
existing JWT access token path working. Add tests for create, authenticate,
expire, revoke, rotate, and reload.

Phase 2: Scoped token authorization.

Decide whether token scopes are independent permissions or a restriction on the
user's ACL grants. Prefer a restrictive model where the final permission is the
intersection of user grants and token scopes.

Phase 3: Admin token API.

Add endpoints to create, list, revoke, and rotate application tokens. The create
response returns the token once. List responses never include token secret
material. The detailed admin API and usage-accounting design lives in
`docs/plans/2026-05-17-application-token-admin-api-usage.md`.

Phase 4: JWT improvements.

Add token id and token kind claims to issued JWTs where appropriate. Decide
whether short-lived JWTs remain stateless or must check a server-side token
record for revocation-sensitive flows.

Phase 5: OAuth or OIDC provider abstraction.

Add provider config for issuer, client id, client secret reference, redirect
URI, allowed domains, and claim mapping. Add callback routes with state and
nonce validation.

Phase 6: External identity mapping.

Define how provider identities map to ACL users: by explicit link, verified
email, provider subject, or admin-created binding. Avoid automatic admin grants.

## Open Questions

Should application tokens authenticate directly, or should they exchange for
short-lived JWT access tokens?

Should token scopes be named actions, ACL grant expressions, or both?

Where should token records be stored: inside ACL XML, alongside ACL storage, or
in a separate operational store?

What is the minimum viable OAuth provider: generic OIDC, GitHub, GitLab, or a
single configured issuer?

How should CLI and Git clients authenticate when OAuth is the human login
method?

Should existing Basic password token issuance remain enabled by default after
OAuth is available?

## Verification

Cover at least these cases:

- existing Basic-to-Bearer token issue continues to work;
- application token is shown once and only a verifier is persisted;
- expired and revoked application tokens are rejected;
- rotated tokens invalidate the previous secret;
- token scopes restrict the user's effective grants;
- token list omits secret material and includes last-used metadata;
- OAuth callback rejects missing state, wrong state, and provider errors;
- external identity mapping does not create unintended admin access;
- token authentication survives ACL reloads according to the selected storage
  model.

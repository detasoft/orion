# Support Short-Lived JWT Rotation and Refresh

Status: todo
Depends on: ../server-identity-migration/TASK.md

Provide purpose-bound short-lived bearer JWTs with signing-key rotation and
automatic renewal. These tokens are reusable until expiration. Each token has
a unique `jti`, but normal validation does not consume it.

## Scope

- Define typed issue, verify, and refresh interfaces and typed failure results.
- Require issuer, audience, subject, purpose, `jti`, issued-at, not-before,
  bounded expiration, and signing key id claims or headers.
- Return `jti` in the validated token identity for audit, targeted revocation,
  refresh-family tracking, and future purpose-specific replay policies.
- Issue new tokens with the active signing alias while retaining previous
  verification aliases until every token they signed has expired.
- Require an authenticated session, persistent machine credential, or another
  explicit renewal authority for refresh; an access JWT cannot renew itself
  indefinitely.
- Add a client token provider that renews before expiration with clock skew,
  jitter, retry policy, single-flight concurrency, and atomic token replacement.
- Keep token contents and renewal credentials out of logs, command lines, and
  persisted diagnostics.
- Test signing-key rollover, old-token verification, automatic refresh,
  concurrent refresh, restart, clock skew, expired tokens, and rejected renewal.

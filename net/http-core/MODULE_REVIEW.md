# Module Review: `net/http-core`

## 14. Excessive token lifetime is reported as failed authentication

**Problem.** A caller with valid Basic credentials requesting `expiresInSeconds: 3601` receives HTTP 401 and
an authentication challenge instead of a request-validation error. The request is authenticated successfully;
its lifetime exceeds the JWT issuer's one-hour limit.

**Sources.** [`OrionAdminIssueTokenRoute`](src/main/java/pro/deta/orion/transport/http/OrionAdminIssueTokenRoute.java)
checks only that the lifetime is positive and maps every `TokenIssueResult.Failure` to 401.
[`OrionAccessControlServiceImpl.issueToken`](../../core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java)
converts the issuer's validation exception into that failure result.
[`JwtAccessTokenService`](../../core/acl/src/main/java/pro/deta/orion/acl/JwtAccessTokenService.java) rejects lifetimes
above 3600 seconds; `JwtAccessTokenServiceTest.rejectsExpirationBeyondShortLivedTokenLimit` covers the boundary.

**Documented behavior.** No HTTP contract for excessive positive lifetimes was found. Nonpositive lifetimes
already use request-validation errors, while the issuer and its tests enforce the upper bound.

**Contract.** Invalid request parameters must not be presented as invalid credentials. Keep the one-hour
issuance limit and the existing 401 response for rejected authentication.

**Minimal repair.** Share the existing lifetime bound with HTTP validation and reject excessive lifetimes with
400 before token issuance. Cover 3600, 3601, a very large positive value, and valid-lifetime authentication failure.

**Alternatives and consequences.** Classifying all issuance failures would also distinguish signing outages
from authentication failures, but expands the result contract. Duplicating the numeric limit risks divergence.

**Confidence.** High from the complete production call chain and existing issuer test; no new HTTP reproduction
was executed during this read-only review.

**Priority signals.** Importance: medium because callers receive misleading authentication failures.
Repair ease: high because the issuer limit already exists and HTTP has a validation-error path.

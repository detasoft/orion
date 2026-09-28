# Module Review: `core/authorization`

## 3. Unused token renewal provider retains a second state and concurrency model

**Problem.** Every authorization build includes `AccessTokenProvider`, but its only caller is its own test.
Token caching, renewal coordination, retry sleeping, jitter, expiry arithmetic, extension interfaces, and a
separate result hierarchy have no production owner.

**Sources.** [Provider](src/main/java/pro/deta/orion/auth/AccessTokenProvider.java#L14) and
[tests](src/test/java/pro/deta/orion/auth/AccessTokenProviderTest.java#L21).
Repository-wide searches found no construction, wiring, or reflective reference outside that test.
The live [SSH issuance path](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalog.java#L193)
uses [refreshToken](src/main/java/pro/deta/orion/OrionAccessControlService.java#L72), implemented by
[ACL service](../acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L792).

**Documented behavior.** The provider comment promises early renewal without duplicate concurrent renewals.
Its introduction in `89b7296f` established implementation and tests; no current documentation or consumer
establishes a requirement to integrate this Java provider. Browser renewal has its own live owner.

**Contract.** Preserve authentication, token issuance and refresh, secret redaction, and real callers' result
types. No production consumer relies on this provider's cache or concurrency guarantees.

**Minimal repair.** Delete the provider and its dedicated tests. Move the independent `TokenIssueResult` and
`TokenRefreshResult` redaction assertions from `tokenResultsDoNotExposeTokenContentsInDiagnostics` into a
surviving test. Retain `TokenRefreshResult` and its actual callers.

**Alternatives and consequences.** Deletion removes an unused internal API and roughly 400 implementation/test
lines without wire or persisted changes. Integrating it would add behavior and ownership without a current
requirement. Repeat caller inspection and verify the dependent reactor before deletion; absence of the old type
must not be asserted by source text or reflection.

**Confidence.** High for repository-local absence of consumers. No external compatibility requirement was found.

**Priority signals.** Importance: low to medium, due to unnecessary security-adjacent state and concurrency
maintenance. Repair ease: high, with local deletion and relocation of independent redaction coverage.

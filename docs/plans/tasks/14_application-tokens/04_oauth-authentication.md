# System OIDC Sign-In for Existing Users

## Required result

Add OIDC sign-in for existing system users through an independent system-level
provider list. Link a verified external account only through an explicitly
configured `OIDC_SUBJECT` credential containing its issuer and subject.
The user retains the roles and grants already configured in the system ACL.
Do not create system users during sign-in or link accounts by matching email.

Organizations keep their own provider lists and secrets. There is no inheritance,
default-provider fallback, or organization override mode. Administrators may
configure the same external provider independently in several scopes.
An empty provider list disables OIDC in that scope.

## Existing model and preserved behavior

Organization provider setup (`82c74b1c`), invited onboarding (`af76d014`),
administrator provider controls (`b69388c3`), and browser session renewal
(`62ba1440`) are already integrated. Reuse their shared discovery, code exchange,
PKCE, nonce/state validation, encrypted session persistence, renewal, and logout.

System users and `OIDC_SUBJECT` credentials already exist in the ACL schema.
Reuse the existing ACL editor for explicit issuer/subject binding. Keep system
and organization identities distinct even when user ids, provider ids, issuer,
subject, or secret names coincide. System providers use only system secrets;
organization providers use only their own organization's secrets.

Preserve Basic/Bearer/SSH authentication, root locking and recovery, organization
invitations, profile completion, current session timeout policies, and server-side
resource authorization. OIDC must not bypass root key enrollment or grant new
permissions. Membership in an organization does not create a system identity.

## Design

- Add system providers to `SystemConfiguration` and XML, using the existing
  `OidcProvider` model. Preserve them in every configuration-copy/update consumer.
- Select system login explicitly with `scope: "system"`; organization login
  continues to require an organization id. Reject conflicting or unknown scopes.
- Extend existing administrator provider APIs to manage system providers and
  encrypted secrets. Keep them restricted to existing system administrators.
- Resolve a verified issuer+subject to exactly one existing system user. Reject
  missing or ambiguous bindings and unavailable accounts. Revalidate before
  issuing or renewing a token; retain current ACL permissions.
- Bind system browser sessions and access tokens to provider settings, secret
  material, account binding, and root authentication generation. Provider removal,
  secret rotation, binding removal, or root locking/recovery invalidates affected
  authority without granting authority in another scope.
- Use the configured public HTTPS origin for the shared callback. Do not add a
  second OIDC implementation, provider registry, or account store.
- Add visible system sign-in/provider settings and explicit OIDC credential
  editing for existing users in the frontend. Keep organization controls local.

## Implementation plan

1. Extend the system model/XML and every configuration-copy consumer; add
   schema/persistence and independent-scope tests.
2. Add explicit system provider selection, secret resolution, existing-account
   lookup, ACL token issue/validation, session renewal, and invalidation to the
   shared backend. Extend administrator provider management and ACL validation.
3. Test system sign-in, restart, refresh/logout, wrong or ambiguous bindings,
   same-email rejection, root locking/recovery, cross-scope confusion, secret
   rotation, provider removal, and unchanged organization onboarding.
4. Add frontend system sign-in, system provider editing, and explicit existing
   user OIDC bindings. Update setup documentation.
5. Run focused tests, full `make test`, and the relevant frontend tests/build.

## Acceptance

An administrator configures a system provider and explicitly links an existing
system user. That user signs in, receives only existing ACL authority, can renew
and log out, and keeps the session across restart while its binding remains
valid. Missing/ambiguous bindings and organization-only users cannot obtain
system tokens. Configuration, binding, and root recovery changes revoke affected
sessions/tokens. XML and unrelated configuration edits preserve provider lists.
No provider or secret is inherited between system and organization scopes.

Live Google or corporate-provider acceptance remains separate in
`../02_hierarchical-orion-configuration/06_oidc-live-provider-acceptance.md`;
this task uses deterministic local provider fixtures for protocol coverage.

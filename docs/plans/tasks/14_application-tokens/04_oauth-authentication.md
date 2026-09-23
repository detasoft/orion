# OIDC Login and Email Invitations

- Owner: codex, session 01a0cd57-6e6e-7083-bffc-2f8231e97bc9, branch `codex/oidc-invitations-01a0cd57`,
  worktree `.worktrees/oidc-invitations-01a0cd57`, paused 2026-09-23 10:25 Europe/Amsterdam;
  next: resolve prerequisite scope with user, then update governing inputs before implementation.

## Required result

Provide one configurable OIDC login mechanism for Google and corporate OIDC
providers. Administrators invite a specific email into an existing organization
with initial roles/team memberships. The recipient authenticates with an
allowed provider, fills in their profile, and becomes an Orion user. Subsequent
logins use the external identity and Orion permissions. Registration is closed
without an invitation. The administrator receives a shareable link; automatic
email delivery is outside this task.

## Current model and dependencies

HTTP uses OrionAuthorizationFilter and existing Bearer verification. User
creation currently uses OrionAdminCreateOrUpdateUserRoute. OrionDocument has
organization users with first/last/email, enabled, credentials, memberships,
and role assignments. Configuration changes can use the existing optimistic
revision-checked persistence; ConfigurationSecrets provides encrypted secret
references. These are the foundation for this task. Inspect actual wiring and
published identity mapping before extending it.

Inspection confirmed a prerequisite: organization users/roles are schema-only.
OrionAccessControlServiceImpl publishes and authenticates only the flat
system accessControl; UserIdentity and GrantAccess consume flat grants.
The agreed organization-based invitation rights therefore depend on
../02_hierarchical-orion-configuration/01_hierarchical-authorization.md.
Do not implement an OIDC-only evaluator or mirror organizational users into
the flat ACL. Await the user's choice between that canonical prerequisite and
an explicitly reduced first version using existing flat ACL permissions.
No implementation edits have been made in the task worktree.

Application-token storage, rotation, and scoped automation tokens remain in
01_model-and-storage.md, 02_admin-api-and-usage.md, and
03_scoped-authorization.md. They are not prerequisites for OIDC browser login
and are not deliverables here. Existing Basic/Bearer/SSH authentication must
continue working. Do not introduce another ACL or user store.

## Design

- Configure trusted OIDC providers with id/display name, issuer, client id,
  encrypted client secret reference, and configured redirect URI. Use discovery
  and a maintained OIDC/JWT library where available; never implement signature
  cryptography manually. Google is a provider configuration, not a separate
  production login path. Organizational allowed-provider policy controls login
  and invitation redemption. SAML and account linking UI are out of scope.
- Persist external identity as issuer plus subject linked to an organization
  user. Email is not identity. Never implicitly link an existing user by email.
  Validate uniqueness and organization boundaries. Changed provider policy,
  disabled/deleted users, and expired sessions must take effect for access.
- Invitation contains organization, normalized email, initial roles/memberships,
  expiry, and a cryptographically random secret whose digest alone is persisted.
  Support administrator creation, safe listing, and revocation. Validate grants
  within the target organization. Do not log tokens or return secrets in lists.
- Authorization code flow binds state, nonce, PKCE, provider, invitation, and
  browser. Verify signature, issuer, audience/authorized party, expiration,
  nonce, and email verification. Require exact intended email at redemption.
  Reject wrong browser/state/provider, replay, expired invitations, and wrong
  emails without consuming valid invitations. Bound transient login state,
  network requests, token sizes, and sessions. Trust only admin-configured
  issuers/endpoints; do not accept arbitrary callback redirects.
- Before completing profile, expose only the onboarding operation, not a fully
  authorized account/session. Validate first/last and user identifier according
  to existing domain rules. Atomically persist user, external identity, and
  invitation consumption using existing configuration concurrency control.
  Concurrent/replayed completion cannot duplicate users or consume another
  invitation. Durable records survive reload/restart; incomplete ephemeral
  login may require restarting sign-in after a restart.
- Use a secure HttpOnly browser session cookie and explicit CSRF defense for
  mutations, including existing HTTP mutation routes when cookie-authenticated.
  Preserve Bearer clients. Provide logout and current-user/session status.
  Use bounded short-lived sessions with fresh user/policy validation; do not
  expose provider tokens or client secrets to the frontend.
- Browser UI provides login/provider selection, invitation entry flow, profile
  completion, error handling, and logout. Add administrator invitation controls
  in the existing frontend where appropriate. Anonymous users must be able to
  reach sign-in; preserve existing admin and session features.

## Implementation plan

1. Trace existing configuration schema/XML translation, persistence, secret
   resolution, scoped principals, token issuance, HTTP authorization/routes,
   frontend auth/API client, and tests. Read local class rules. Extend the
   existing canonical model with provider policy, invitations, and identities;
   update every real constructor/translator/consumer that copies those models.
2. Cover and implement durable invitation management and atomic redemption,
   identity uniqueness, organization isolation, expiry/revocation, reload,
   concurrent completion, and failure without partial persistence.
3. Implement shared OIDC discovery/code exchange/token verification with native
   HTTP timeouts, browser-bound one-time login transactions, and short-lived
   onboarding/session state. Integrate existing permission evaluation and
   secret resolution without preserving duplicate authorization models.
4. Register public authentication and protected invitation/profile/session
   routes. Add cookie-aware authentication, logout, CSRF checks, appropriate
   no-store headers and sanitized errors. Preserve existing Bearer/SSH paths.
5. Add browser login, first-use profile, and invitation administration UI with
   behavior tests. Document concrete Google and corporate OIDC setup, secret
   provisioning, redirect URL, invitation use, expiry, and limitations in an
   appropriate existing documentation location.
6. Exercise the flow with a controlled local OIDC provider fixture, including
   both Google-shaped and corporate issuer configuration. Test normal login,
   invitation onboarding, repeat login, wrong email/state/nonce/audience/issuer,
   unverified email, duplicate/replayed callbacks, disabled users, revoked and
   expired invitations, concurrent redemption, restart/reload, logout, CSRF,
   and unchanged Bearer access. Run focused tests via make run-test and the
   complete required make test check; run frontend tests/build as applicable.

## Acceptance

The browser and admin routes are wired into the running application. A user can
accept an email-bound invitation through either configured OIDC provider, set a
profile, obtain exactly the invitation's permissions, logout, and log in again.
An unrelated/unauthorized external account cannot create, link, or access a user.
Invitation and identity persistence are verified through real supported APIs.
Configuration round trips preserve all new and existing fields. Secrets remain
protected. Existing SSH/API login continues to pass its tests. Real external
provider smoke tests require deployment credentials and are reported separately
from deterministic local verification.

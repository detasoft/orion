# System OIDC Defaults and Organization Overrides

## Integrated baseline and remaining work

Organization-scoped OIDC provider configuration is integrated in `82c74b1c`,
invited sign-in and onboarding in `af76d014`, provider setup documentation in
`29f8379a`, administrator provider controls in `b69388c3`, and browser session
renewal in `62ba1440`. Existing deterministic tests cover onboarding, persistence,
protocol rejection, organization isolation, and browser session behavior.
This inventory inspected source and test coverage; it did not rerun those tests.

The remaining implementation is system-level OIDC defaults and organization
inheritance/override controls, including explicitly disabling OIDC, resolving
secrets in the provider's owning scope, and invalidating sign-in attempts and
sessions when the effective configuration changes. `SystemConfiguration` has no
OIDC provider list, and current sign-in reads only organization-local providers.
Do not close this task based on the integrated organization-only baseline.

Resume this implementation after the current worktree cleanup. Live Google or
corporate-provider acceptance remains a separate task in
`../02_hierarchical-orion-configuration/06_oidc-live-provider-acceptance.md`.

## Required result

Add system-wide OIDC provider defaults and let each organization inherit them,
replace them with its own provider list, or explicitly disable OIDC. Preserve
the integrated invitation, Google/corporate sign-in, and profile-completion flow.
Organization users see and access only their organization's repositories and
other scoped resources.
System-wide settings remain restricted to existing system administrators.
Use one shared OIDC implementation. Administrator receives a shareable invitation
link; automatic email sending is not required.

## Scope and preserved behavior

The user explicitly narrowed away full hierarchical authorization, role
inheritance, allow/deny precedence, and team-level permission design. Those
were completed separately in 1f09d248 and are outside this minimal OIDC scope. Do not
implement them in this change or create temporary role/evaluator frameworks.
All enabled members have the same basic read access to organization resources;
repository create/write/force and administrative mutations remain denied until
separately authorized scope is defined. Do not grant global
administration, global secrets/configuration, or access to another organization.
Existing system ACL, admin/root recovery, SSH, Basic/Bearer behavior is preserved.
OIDC is the new login method; existing paths must enforce the same organization
boundary when handling an organization principal.

## Current model and smallest extension

OrionDocument already stores organization-local providers, users and invitations.
OrganizationAccounts and OrionOidcRoute implement invited sign-in and account
resolution. PrincipalAddress distinguishes system and organization principals,
and ConfigurationScope and RepositoryAddress identify resource ownership.
Extend the existing provider selection and secret-resolution paths to use the
effective configuration and its owning scope.
Do not duplicate organizational users into system ACL, introduce a parallel user
store, or treat an organizational login as a same-name system user.

Reuse OrionDesiredState and existing revision-checked configuration persistence
for organization configuration, users, identities and invitations. Reuse
ConfigurationSecrets for provider secrets, extending organization secret
resolution minimally if necessary. Durable new configuration fields must round
trip XML and survive every existing document-copy/update consumer.

## Design

- Support OIDC configuration at root/system and within each organization: id/name,
  issuer, client id, encrypted secret reference, configured redirect URI.
  Root providers are shared defaults. An organization's explicitly configured
  provider list replaces the defaults for that organization; an absent override
  inherits root providers. An explicit empty override disables OIDC there.
  Preserve this distinction in XML and UI, with simple visible controls.
  Provider secret references resolve in the scope owning that configuration;
  never allow a similarly named organization secret to shadow root material.
  Effective configuration changes invalidate pending sign-in and sessions as
  appropriate. Root OIDC config does not confer root/system-user authority or
  bypass invitations; organization selection remains required for member login.
  Support Google and corporate providers through the same small provider list.
  No provider plugin registry.
  Use discovery and a maintained existing/new OIDC/JWT library as needed.
  Configuration and invitations are managed by existing system administrators
  in the minimal version; no new organization-admin role machinery.
- Link issuer+subject to OrganizationUser, with uniqueness in organization
  scope. Same email never implicitly links to another existing account.
  Organization/provider selection is bound to the login transaction. Subsequent
  sign-in selects organization and configured provider; invitation supplies both
  the organization and allowed provider choices on first use.
- Invitation persists organization, normalized email, expiry and only the hash
  of a cryptographically random secret. Support create/list/revoke. Do not expose
  secrets on list or logs. Expired/revoked/wrong-email invitations cannot redeem.
- Authorization code flow validates state, nonce, PKCE, issuer, signature,
  audience/authorized party, expiration, verified email, and exact invited email.
  Bind each one-time transaction to the browser, organization and provider.
  Never trust arbitrary callback redirects/issuers. Use native HTTP deadlines
  and bounded state/session/token data. Google is ordinary configuration.
- First sign-in yields only onboarding authority. Recipient sets profile
  details (first/last and user identifier as existing domain requires).
  Atomically create user + external identity and consume invitation via existing
  optimistic persistence. Concurrent callbacks/completion cannot duplicate
  users or create partial state. Retry does not consume an unrelated invitation.
- Use short-lived secure HttpOnly browser sessions, logout and current-user
  status. Reject CSRF on cookie-authenticated mutations, including existing
  mutation routes. Preserve Bearer clients. Revalidate current user enabled
  state, organization existence and provider policy; no permanent rights
  captured at login. Do not expose provider tokens/secrets to the browser.
- Organization ownership is enforced server-side, both list filtering and
  individual-resource operations. Matching names in another organization,
  unqualified paths, forged IDs, direct URLs and stale sessions cannot bypass
  it. Reuse qualified principal/resource types in HTTP/SSH/decision consumers.
  Replace DecisionRegistry's unconditional authorization where organization
  users can reach it; unscoped/global decisions stay system-only.
- Expose only organization-safe data to ordinary members. Keep global admin
  APIs restricted rather than filter sensitive global configuration payloads.
  Frontend ordinary-member views list own organization repositories/resources,
  without calling/exposing global admin APIs. Existing operator UI remains for
  system administrators. Resources without a trustworthy organization owner
  stay unavailable to ordinary members rather than inventing ownership.

## Implementation plan

1. Inspect the existing schema/XML mapper, document-copy consumers, provider
   selection, secret resolution, administrator APIs/UI, and session validation.
2. Extend the existing model with system providers and distinguish absent,
   explicitly empty, and non-empty organization overrides in persistence.
3. Resolve effective providers through one production path. Use secrets from
   the owning scope and retain invitation and organization-access boundaries.
4. Revalidate pending sign-in and browser sessions against effective provider
   configuration, including default changes, overrides, disable, and secret
   rotation. Preserve the existing session renewal and timeout policies.
5. Add administrator system-provider settings and visible organization controls
   for inheritance, replacement, and disable; update setup documentation.
6. Test inherited defaults, overrides, disable, persistence/reload, same provider
   and secret ids across scopes, secret shadowing rejection, configuration-change
   invalidation, and existing onboarding/isolation/session behavior. Run focused
   make test plus full make test; frontend tests/build as needed.

## Acceptance

Administrator can configure root defaults and organization OIDC overrides and
issue/revoke invitations. Test inherited defaults, organization override and
explicit disable, including same provider ids/secret ids across organizations.
Recipient signs in, completes profile once, and sees their organization's
repositories/resources. Direct requests cannot reach another organization or
global administration. Both Google-shaped and corporate OIDC configurations use
one tested flow. Persistence/reload, safe secrets, atomic redemption and existing
system login are covered. No full hierarchical-role engine is added. Actual
external-provider smoke testing needs deployment credentials and is reported
separately from deterministic local tests.

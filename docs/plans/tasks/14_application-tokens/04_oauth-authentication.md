# Organization OIDC Login and Email Invitations

- Owner: codex, session 01a0cd57-6e6e-7083-bffc-2f8231e97bc9, branch `codex/oidc-invitations-01a0cd57`,
  worktree `.worktrees/oidc-invitations-01a0cd57`, started 2026-09-23 10:27 Europe/Amsterdam.

## Required result

User-approved minimal scope: configure OIDC at organization level, invite an
email into that organization, let the recipient sign in through Google or a
corporate OIDC provider and fill in their profile. Organization users see and
access only that organization's repositories and other scoped resources.
System-wide settings remain restricted to existing system administrators.
Use one shared OIDC implementation. Administrator receives a shareable invitation
link; automatic email sending is not required.

## Scope and preserved behavior

The user explicitly narrowed away full hierarchical authorization, role
inheritance, allow/deny precedence, and team-level permission design. Those
remain in ../02_hierarchical-orion-configuration/01_hierarchical-authorization.md
and are NOT prerequisites for this minimal organization boundary. Do not
implement them in this change or create temporary role/evaluator frameworks.
All enabled members have the same basic read access to organization resources;
repository create/write/force and administrative mutations remain denied until
separately authorized scope is defined. Do not grant global
administration, global secrets/configuration, or access to another organization.
Existing system ACL, admin/root recovery, SSH, Basic/Bearer behavior is preserved.
OIDC is the new login method; existing paths must enforce the same organization
boundary when handling an organization principal.

## Current model and smallest extension

OrionDocument contains organizations and OrganizationUser. PrincipalAddress
already distinguishes system and organization principals, and ConfigurationScope
and RepositoryAddress identify resource ownership. Actual authentication is
currently flat system ACL, so add the minimal organizational principal lookup
and organization-ownership checks at existing authentication/access boundaries.
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

1. Inspect schema/XML mapper, configuration persistence/copy consumers, secret
   resolution, UserIdentity, ACL/token lookup, access rules, resource names,
   HTTP/SSH and decision consumers, frontend API/client and existing tests.
2. Add minimal organization-level providers, invitations and external identities
   to existing configuration/user model; update all real serialization and copy
   paths. Add durable invitation operations and atomic profile completion.
3. Add canonical organization identity resolution and shared organization
   ownership checks. Preserve system identity wire/token compatibility and
   existing ACL behavior. Provide member-safe resource listing/access routes
   through existing mechanisms; do not expose global admin state.
4. Implement generic OIDC browser flow, sessions/CSRF and registered routes.
   Use a local controlled OIDC provider fixture for protocol behavior tests.
5. Add browser login/provider selection, invitation onboarding, member view,
   logout, administrator root/organization provider settings and invitation controls. Document
   Google/corporate configuration and secret provisioning in appropriate docs.
6. Verify normal onboarding/repeat login, wrong email, expired/revoked invite,
   callback/state/nonce/signature/audience/issuer failures, replay/concurrency,
   same-name accounts, cross-organization list and direct-access denial,
   disabled user/policy reload, logout/CSRF and unchanged system Bearer/SSH.
   Run focused make run-test plus full make test; frontend tests/build as needed.

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

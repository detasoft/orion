# Verify Organization Sign-in with a Real OIDC Provider

Status: todo
Depends on: completed organization OIDC setup and invitation onboarding,
per-provider session timeouts (`86742bd5`), encrypted session persistence (`81a1b43f`).

## Required result

Verify the complete browser sign-in journey against one real provider: Google
or an organization-managed OIDC provider. An invited user completes their profile,
accesses only their organization's resources, renews their session, continues
after an Orion restart, and signs out successfully.

## Prerequisites

- A designated test Orion instance with a reachable public HTTPS URL and
  persistent data directory and key material.
- An application registered with the selected provider, with callback URL
  `<publicUrl>/api/auth/oidc/callback`, and an available test account.
- An Orion administrator and two test organizations with distinct repositories
  and other organization-owned resources for isolation checks.
- Supply the provider client secret through the existing administration UI;
  do not put credentials, cookies, or tokens in task text or test evidence.

## Design

Use the existing organization OIDC configuration, invitation flow, browser UI,
request-based activity tracking, and encrypted session persistence. This task
validates the deployed behavior; it does not introduce another authentication
path or replace deterministic tests with calls to a live provider.

Use short timeout settings on the test connection when testing expiration.
Restore the intended settings afterward. Keep inactivity and mandatory
reauthentication separate, including the disabled mandatory timeout (`0`).
Any discovered defect must have a reproducible scenario and appropriate
regression coverage before a production fix is considered complete.

## Execution plan

1. Confirm the test environment and provider application, configure the public
   URL and the organization's connection through the administration UI, and
   verify the saved configuration survives restart without exposing its secret.
2. Invite the test email, follow the invitation in a fresh browser session,
   authenticate with the provider, complete the profile, and verify a subsequent
   sign-in reuses the existing user.
3. Check repository listings and direct resource requests across both
   organizations using the signed-in user rather than the administrator.
4. Exercise automatic token renewal, activity-based extension, inactivity expiry,
   and mandatory reauthentication with temporary test timeout settings.
5. Restart Orion with an active browser session, verify continued authenticated
   work and renewal, then sign out and verify the session remains revoked after
   another restart.
6. Record the tested Orion revision, provider, non-secret configuration details,
   observed results, and any remaining blockers. Update the existing README
   provider instructions if the live setup reveals missing steps.

## Acceptance

- An invitation works for its intended email; another account cannot use it
  to join the organization. Repeated sign-in does not create duplicate users.
- The user sees only their organization's repositories and other scoped data;
  direct requests cannot bypass that isolation.
- Automatic renewal works without an interactive login while the session is
  valid. Working requests extend inactivity; refresh and excluded background
  requests alone do not. Expired sessions cannot be revived.
- A nonzero mandatory timeout requires fresh provider authentication even with
  regular activity; `0` leaves only the inactivity limit in force.
- An active session survives Orion restart with its original deadlines and
  identity. Signing out prevents further session renewal, including after restart.
  Already-issued access tokens retain their documented expiry semantics.
- Evidence distinguishes live-provider results from automated test results and
  lists any untested cases explicitly; a missing environment is a blocker,
  not a successful acceptance result.

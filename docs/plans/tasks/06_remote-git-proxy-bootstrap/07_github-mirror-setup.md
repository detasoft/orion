# Configure and Accept an Orion Repository Mirror on GitHub

Status: todo
Depends on: shared Git credential resolution in [06/02](02_proxy-config-and-secrets.md),
[07/01 primary upstream runtime](../07_external-git-repository-sync/01_primary-upstream.md).

## Required result

An operator can attach an existing GitHub repository to an ordinary Orion
repository using the admin interface, safely supply or select a token credential,
and observe Orion commits arrive at GitHub. The setup survives process restart.
This leaf delivers configuration/API/UI integration and acceptance; it does not
reimplement the synchronization engine owned by 07/01.

## Design

Reuse `OrionDocument.Repository`, `RepositoryRemote`, the reserved `upstream`
alias with `PRIMARY` role, `RemoteProvider.GITHUB`, and scoped credential
references. The setup accepts an HTTPS repository URL such as
`https://github.com/OWNER/REPOSITORY.git`. The token is provided separately in a
write-only input or selected by reference; read responses never return it.

The initial behavior follows 07/01: reconcile compatible branches at attachment,
then asynchronously push Orion branch updates upstream. GitHub-side changes
after attachment are detected and require explicit reconciliation/retry. Do not
label this continuous inbound or automatic bidirectional synchronization.
All branches are included initially; tags, force updates, and deletions are not
implicit. Branch filtering and webhooks remain independent later deliveries.

Local Orion reads/writes stay available during remote outage or conflict. The
UI reports attachment, active, offline, conflicted, and disabled state using the
existing runtime model, with safe diagnostics and conflicting branch tips.
Expose retry and credential replacement through authorized, audited commands.
Disabling/removing a remote preserves local refs and never deletes GitHub data.

## Implementation plan

1. Extend existing repository admin commands/projections to configure the
   existing remote model, validate the URL/policy and credential scope, and
   reject concurrent stale configuration updates.
2. Connect saved desired state and credential changes to the 07/01 lifecycle;
   return a pending/failed/active result reflecting runtime observation rather
   than claiming that saving configuration completed synchronization.
3. Add a repository Mirror section: GitHub URL, separate credential input or
   reference, connect, state, retry, replace credential, and disable.
4. Add a user-facing setup example to existing documentation, including initial
   reconciliation, ordinary Orion clone/push URLs, conflicts, and restart.
5. Exercise the complete admin-to-runtime journey using a deterministic local
   native upstream through the provider-neutral transport seam. Preserve strict
   GitHub URL validation in production; test fixtures must not relax it.
6. Document an optional real GitHub smoke test using an explicitly designated
   test repository/credential. Do not create a repository or mutate an arbitrary
   account as part of automated acceptance.

## Acceptance

- Starting with a populated upstream, configure the mirror through the real
  admin API/UI and clone matching history from Orion.
- Push a new commit to Orion; observe the same branch object ID upstream and
  an up-to-date mirror status without blocking the original push on GitHub.
- Restart Orion and verify configuration, credentials, and pending work reload
  without duplicate upstream entries or lost desired updates.
- Interrupt the remote, accept local work, restore connectivity, and observe
  retry converge. A lost push response is idempotently reconciled.
- Introduce an external conflicting commit; no force push occurs, both tips
  remain available, and explicit operator reconciliation/retry restores sync.
- Reject wrong credentials and cross-scope administration safely; replacing
  credentials enables a later retry without revealing old or new values.
- Verify local API/UI, runtime, and native transport integration plus the full
  JVM suite. Report the real GitHub smoke result separately as run or not run.

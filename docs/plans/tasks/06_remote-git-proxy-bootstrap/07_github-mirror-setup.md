# Configure and Accept an Orion Repository Mirror on GitHub

Status: todo
Depends on: completed shared Git credential resolution (`a8cf31a5`, `8750c1ee`),
[07/03 bidirectional runtime](../07_external-git-repository-sync/03_github-commit-replication.md)
(which builds on 07/01).

## Required result

An operator can attach an existing GitHub repository to an ordinary Orion
repository using the admin interface, safely supply or select a token credential,
and observe commits made on either side arrive on the other. Fast-forwards and
conflict-free merges run automatically; actual merge conflicts require manual
resolution. The setup survives process restart.
This leaf delivers configuration/API/UI integration and acceptance; it does not
reimplement the synchronization engine owned by 07/01 and 07/03.

## Design

Reuse `OrionDocument.Repository`, `RepositoryRemote`, the reserved `upstream`
alias with `PRIMARY` role, `RemoteProvider.GITHUB`, and scoped credential
references. The setup accepts an HTTPS repository URL such as
`https://github.com/OWNER/REPOSITORY.git`. The token is provided separately in a
write-only input or selected by reference; read responses never return it.

The setup enables bidirectional branch synchronization: reconcile compatible
branches at attachment, asynchronously push Orion updates, and periodically
fetch compatible GitHub changes into Orion. Fast-forward when possible; otherwise
create and publish a merge preserving both parents when the three-way merge is
conflict-free. Display both directions and automatic clean merging before
connection. Request manual resolution only when merging cannot safely complete;
divergence alone is not a merge conflict.
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
2. Connect saved desired state and credential changes to the shared 07/03 lifecycle;
   return a pending/failed/active result reflecting runtime observation rather
   than claiming that saving configuration completed synchronization.
3. Add a repository Mirror section: GitHub URL, separate credential input or
   reference, bidirectional behavior, connect, state, sync now, retry, replace
   credential, and disable. Expose local/upstream tips and conflicting paths for
   each merge conflict, distinguish policy failures from content conflicts, and
   explain how to fetch both histories, merge or rebase explicitly, push the
   reconciled commit to Orion, and retry. Retry must never force a resolution.
4. Add a user-facing setup example to existing documentation, including initial
   reconciliation, writes on either side, ordinary Orion clone/push URLs,
   scheduled inbound latency, manual sync, conflicts, and restart.
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
- Push a compatible commit directly to GitHub after attachment; within the
  scheduled observation interval, clone/fetch the same commit from Orion.
  Manual sync can observe it sooner and must use the same conflict policy.
- Restart Orion and verify configuration, credentials, and pending work reload
  without duplicate upstream entries or lost desired updates.
- Interrupt the remote, accept local work, restore connectivity, and observe
  retry converge. A lost push response is idempotently reconciled.
- Make divergent but mergeable edits on both sides, including independent edits
  in one text file. Observe an automatic clean merge on both sides preserving
  both changes and both parent histories, without operator confirmation.
- Make conflicting edits on both sides, including during an outage. Both tips
  remain available, neither side is overwritten, and the UI reports the conflict.
  Explicitly reconcile both histories, push the result to Orion, and retry;
  verify both sides converge and a concurrent new GitHub commit is rechecked.
- Verify inbound commits do not echo back as redundant outbound work and repeated
  sync/restart converges without an unbounded event loop.
- Reject wrong credentials and cross-scope administration safely; replacing
  credentials enables a later retry without revealing old or new values.
- Verify local API/UI, runtime, and native transport integration plus the full
  JVM suite. Report the real GitHub smoke result separately as run or not run.

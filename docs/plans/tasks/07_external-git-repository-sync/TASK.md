# External Git Repository Synchronization

Status: active

## Required result and current model

Attach ordinary Orion repositories to external Git remotes through the existing
provider-neutral `git-sync` module. GitHub is a transport profile. Schema and
attachment/persistence foundations already exist (`146b9e76`, `949644e6`);
runtime outbound processing, retry, audits, and composition remain unfinished.
Do not interpret an integrated foundation as completed synchronization.

## Design and delivery boundary

The required result accepts branch changes in both Orion and GitHub. Compatible
changes converge in both directions. Divergent histories merge automatically
when conflict-free; actual merge conflicts preserve both histories for manual
resolution. Local repository use remains available during outage/conflict.
No automatic rebase, force push, choice of one conflicting side, or deletion is
implied. Operational queues, observations, conflicts, and last-run state remain
outside `orion.xml`. The claimed primary-upstream leaf remains the outbound
foundation; its completion alone does not satisfy this stream.

Transparent bootstrap proxies remain in
[06](../06_remote-git-proxy-bootstrap/TASK.md). They have synchronous upstream
read/write semantics and are not the background mirror engine.
The operator-facing GitHub setup and cross-stream acceptance are owned by
[06/07](../06_remote-git-proxy-bootstrap/07_github-mirror-setup.md), using the
shared credential boundary from 06/02. Those tasks do not take over the existing
primary-upstream claim.

## Dependencies and acceptance

Complete the baseline runtime, extend that runtime with scheduled/manual inbound
synchronization, then verify the bidirectional setup journey. Inbound runtime
must not depend on setup acceptance, which consumes it. Branch filtering and
webhook wakeups extend the same runtime and do not block the first delivery.
Reuse one queue/state owner and remote schema. SSH/GitHub App credentials, extra
outbound remotes, and tags must not block the initial HTTPS-token setup.

Acceptance covers initial import, changes from both sides, durable retry after
restart, lost responses, concurrent divergence, safe diagnostics, loop prevention,
automatic clean merges, and manual reconciliation/retry through a configured
repository when needed. Fast-forwards and conflict-free three-way merges require
no confirmation; conflicting changes are never resolved by silently picking a side.

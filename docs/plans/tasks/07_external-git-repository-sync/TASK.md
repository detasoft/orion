# External Git Repository Synchronization

Status: active

## Required result and current model

Attach ordinary Orion repositories to external Git remotes through the existing
provider-neutral `git-sync` module. GitHub is a transport profile. Schema and
attachment/persistence foundations already exist (`146b9e76`, `949644e6`);
runtime outbound processing, retry, audits, and composition remain unfinished.
Do not interpret an integrated foundation as completed synchronization.

## Design and delivery boundary

The baseline reconciles compatible branches on attachment and then sends Orion
updates upstream asynchronously. Local repository use remains available during
outage/conflict. No automatic merge, force push, or continuous inbound import is
implied. Operational queues, observations, conflicts, and last-run state remain
outside `orion.xml`.

Transparent bootstrap proxies remain in
[06](../06_remote-git-proxy-bootstrap/TASK.md). They have synchronous upstream
read/write semantics and are not the background mirror engine.
The operator-facing GitHub setup and cross-stream acceptance are owned by
[06/07](../06_remote-git-proxy-bootstrap/07_github-mirror-setup.md), using the
shared credential boundary from 06/02. Those tasks do not take over the existing
primary-upstream claim.

## Dependencies and acceptance

First complete the baseline runtime and its setup journey. Branch filtering is
an extension of that same runtime. The later GitHub replication leaf must reuse
it for inbound/webhook behavior rather than create another queue, remote schema,
or worker engine. SSH/GitHub App credentials, extra outbound remotes, and tags
are extensions and must not block the initial HTTPS-token setup.

Acceptance covers initial import, outbound convergence, durable retry after
restart, lost responses, upstream conflicts, safe diagnostics, and explicit
operator retry through a configured repository. Inbound replication additionally
requires explicit conflict and loop-prevention behavior before activation.

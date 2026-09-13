# Extend the Existing GitHub Mirror With Inbound Replication

Status: todo
Depends on: [07/01](01_primary-upstream.md),
[07/02](02_branch-filtering.md),
[GitHub mirror setup and acceptance](../06_remote-git-proxy-bootstrap/07_github-mirror-setup.md).

## Required result

An explicitly enabled mirror can receive GitHub branch updates through manual
sync, scheduled refresh, or verified push webhooks. The initial outbound-only
setup retains its behavior unless inbound synchronization is explicitly enabled.

## Current model and scope

Reuse the existing repository remote schema, native Git client, `git-sync`
attachment planning, durable work/state storage, credentials, and admin surfaces.
Do not create a second mirror configuration store, generic job framework, lease
service, or GitHub REST commit writer. GitHub repository creation, webhook
installation automation, GitLab support, SSH/App credentials, tag mirroring,
force/delete policies, and automatic merge/rebase are outside this leaf.

The older plan duplicated the baseline engine and setup now owned by 07/01 and
06/07. This leaf contains only the remaining inbound/webhook behavioral delta.

## Design and invariants

Model continuous inbound permission explicitly in the existing remote desired
state. Extend one per-remote execution owner to serialize attachment, outbound
push, audit, and inbound fetch. Reuse its durable retry/coalescing mechanism;
add durable facts only where restart or delivery deduplication requires them.

Fetch selected upstream refs and publish tracking state before comparing with
live Orion heads. Apply only compatible creates/fast-forwards using expected old
IDs and the same selected branch set as outbound work. Divergence preserves both
histories and reports conflict; a stale local snapshot requires replanning.
Never silently overwrite local-only work or bypass protected-ref policy.

A GitHub push webhook verifies a bounded raw request with the configured secret
before trusting parsed fields, checks the repository binding, deduplicates the
delivery, and enqueues a refresh. It does not fetch or push inline. Webhook data
is a hint; Git advertisement and validated objects determine repository state.
Manual and scheduled refresh remain available when webhook delivery is lost.

Incoming ref updates carry sufficient origin information to avoid enqueueing
an equivalent outbound push. An outbound push followed by its webhook converges
to a no-op. Do not use commit-message markers or a second source of live refs.
The same expected-ID and conflict checks apply when both sides change.

## Implementation plan

1. Extend existing desired state and validation for explicit inbound enablement,
   preserving the baseline behavior of stored outbound-only remotes.
2. Add manual/scheduled inbound work to the existing coordinator and retry path;
   reuse branch selection, native publication, and safe failure reporting.
3. Add origin-aware event/coalescing behavior sufficient to suppress loops,
   including restart and lost-response cases.
4. Add the GitHub webhook route with bounded input, signature verification,
   unambiguous repository mapping, and durable delivery deduplication.
5. Extend existing mirror administration with inbound enablement/manual refresh
   and safe status; document manual webhook setup in existing user docs.
6. Verify both one-way flows together before enabling them for one repository.

## Acceptance

A manual or scheduled fetch imports a compatible selected GitHub branch and
rejects divergence without overwriting Orion work. Concurrent local changes are
replanned safely. Wrong/missing signatures and unknown repository mappings
cannot enqueue trusted work or expose private configuration.

Duplicate webhooks, lost responses, remote outage, and restart do not lose desired
work or create an unbounded loop. Outbound push followed by webhook is a no-op
when tips match; an inbound update does not echo back as redundant outbound work.
Branch filters apply identically to tracking, live updates, and conflicts.

Test the existing admin/runtime/native-transport path with deterministic local
upstreams, signed webhook fixtures, meaningful conflict and authorization cases,
and the full JVM suite. Real GitHub acceptance is a separately reported smoke
test against an explicitly designated repository; it is not an automated
prerequisite for local verification.

# Wake Bidirectional Mirrors From GitHub Push Webhooks

Status: todo
Depends on: [07/03](03_github-commit-replication.md),
[06/07 setup](../06_remote-git-proxy-bootstrap/07_github-mirror-setup.md).

## Required result and design

Verified GitHub push notifications wake the existing mirror reconciliation
without waiting for the next scheduled observation. Polling/manual sync remain
available when deliveries are lost; webhooks are not required for bidirectional
correctness. Reuse the existing coordinator, queue, credentials, and admin UI.

Verify a bounded raw request against the configured webhook secret before
trusting parsed fields. Require unambiguous repository binding, deduplicate
provider deliveries durably, and enqueue a refresh without Git I/O in the HTTP
handler. A webhook is a hint: Git advertisement and validated objects determine
state. An outbound push followed by its webhook is a no-op when tips match.

## Implementation plan

1. Add authorized webhook-secret configuration using the existing credential
   owner and document manual GitHub webhook setup in existing user docs.
2. Add bounded signature verification, repository mapping, delivery deduplication,
   and enqueueing through the existing work owner.
3. Extend safe admin diagnostics and test signed deliveries, duplicates, lost
   deliveries, restart, unknown bindings, and outbound-push echoes.

## Acceptance

Valid deliveries trigger the same conflict-safe reconciliation as polling.
Invalid/missing signatures and unknown bindings cannot enqueue trusted work or
expose configuration. Duplicate/replayed deliveries and restart do not create
unbounded work; lost deliveries converge through polling. No Git operation runs
inline in the handler. Verify API/runtime behavior with signed local fixtures
and the full JVM suite; real GitHub smoke results are reported separately.

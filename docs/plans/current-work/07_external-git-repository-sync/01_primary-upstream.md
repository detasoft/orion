# Implement Primary Upstream Synchronization

Status: todo
Owner: codex, session 6e3b, paused 2026-09-04 01:25 Europe/Amsterdam;
next: implement serialized outbound processing, retry, and minute audits.
Depends on: completed repository and mirror configuration foundation

Attach one reserved `upstream` remote, reconcile compatible state when Orion
starts or reconnects, and then synchronize changes outbound from Orion.

## Scope

- Support the GitHub HTTPS token profile over provider-neutral Git transport.
- Fetch all branches on attach into `refs/remotes/upstream/*`; do not sync tags.
- Plan all branches before changing live refs or pushing, and apply compatible
  local creates and fast-forwards atomically.
- Leave live refs and the external repository independent when any branch has
  diverged; expose all conflicting branch tips for operator reconciliation.
- Retry attachment explicitly after the operator resolves divergence in Orion.
- Coalesce outbound ref updates durably and retry transient failures with
  backoff without blocking local repository use.
- Audit upstream refs once per minute with staggering and no per-remote overlap;
  detect out-of-band remote changes without importing them after attachment.
- Cover startup import, Orion-ahead push, mixed compatible branches, divergence,
  restart recovery, lost responses, expected-ID races, and manual retry.

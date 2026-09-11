# Report Discovered Sessions over the Control Connection

Status: todo
Depends on: completed authenticated AgentD reconnect lifecycle and completed
session discovery.
Server contract: session reconciliation completed in `f518679c`, `8168ae6e`, and `b751cfc9`.

Expose existing local session discovery through authenticated Agent control
messages, including after reconnect and while local sessions continue offline.

## Scope

- Reuse `SessionDiscovery`, `SessionRegistry`, and `SessionDiscoveryMonitor`
  rather than introducing a second scan, registry, or persistent snapshot.
- Answer `REQUEST_SESSION_LIST` after each authenticated connection from a
  completed discovery snapshot; handle requests arriving during the initial scan.
- Map live, completed, degraded, and otherwise discovered sessions to bounded
  protocol descriptors with their available journal ranges and safe diagnostics.
- Define ordered session-status updates and refresh behavior using the existing
  `SESSION_LIST` and `SESSION_STATUS` messages. Drop stale connection deliveries
  and report capacity failures explicitly; never silently truncate a session set.
- Keep discovery active offline, isolate malformed or unreachable sessions,
  and use the recovered snapshot when a new connection requests its session set.

## Design

### Snapshot ownership and readiness

`SessionRegistry` remains the single in-memory owner of discovered sessions. It
publishes immutable replacements and exposes completion of its first
reconciliation so an early `REQUEST_SESSION_LIST` waits for a real disk scan
instead of treating the registry's construction-time empty value as a completed
empty scan. Reconciliation continues independently of connection state.

The control lifecycle observes registry replacements without adding another
registry or durable AgentD state. A request sends one complete snapshot. A new
or removed session causes a complete refresh; a changed existing session causes
one `SESSION_STATUS`. Control sends are serialized in registry publication order.
Repeated updates are harmless because both report forms are idempotent.

### Descriptor mapping

Discovery records each readable journal's first and last complete event IDs by
using the existing journal reader. An empty initialized journal has no range;
partial active tails do not extend the advertised range. Journal read failures
degrade only their session.

Map a live host with a live child to `RUNNING`, a live host with an exited child
to `EXITED`, an unreachable host to `LOST`, and discovery or journal failures to
`DEGRADED`. Diagnostics use bounded fixed descriptions rather than filesystem
paths or raw exception messages. A malformed directory without a trustworthy
session ID remains in discovery issues but cannot form a protocol descriptor and
is omitted without disturbing valid peers.

### Connection fencing and failures

Each report captures the authenticated `AgentConnection` that requested or
triggered it. A replacement or disconnect invalidates pending work and failed
sends apply only to that captured connection. Reconnect uses the newest completed
registry snapshot, including changes discovered while offline.

Encoding and transport-capacity failures are recorded explicitly by AgentD and
invalidate the affected connection. Reconnect retries against the newest
snapshot, while a still-oversized snapshot remains an observable failure instead
of appearing to succeed. The complete set is never split or truncated because
the server reconciles each `SESSION_LIST` as an independent full report.

The server reconciliation owner consumes `SESSION_STATUS` through the same
ownership-checked, durable update path as a singleton report. Obsolete server
connections remain fenced by `AuthenticatedAgentConnections`.

## Implementation plan

1. Enrich discovery snapshots with complete journal ranges and add the bounded
   `LocalSession` to `SessionDescriptor` mapping. Cover live, exited, lost,
   degraded, empty, partial-tail, invalid-metadata, and journal-failure cases.
2. Add initial-scan readiness and ordered registry replacement observation, then
   make the authenticated Agent control lifecycle answer list requests and send
   refresh or status updates with connection fencing and explicit send failure.
   Cover empty and populated requests, initial-scan races, concurrent changes,
   repeated requests, offline discovery, reconnect, replacement, and oversized
   reports.
3. Make server reconciliation apply `SESSION_STATUS` using its existing durable
   ownership checks. Verify current and obsolete connections, foreign ownership,
   and durable failure behavior.
4. Verify the reporting lifecycle with the discovery monitor as a real producer,
   then remove this completed task node through the task runner. Assembly in the
   AgentD entry point and shared live-server acceptance remain owned by the next
   sibling task.

## Acceptance

- Tests cover empty and populated lists, initial-scan races, concurrent local
  changes, completed sessions, invalid metadata, and oversized reports.
- Sessions created or changed while offline appear after reconnect; repeated
  requests and connection replacement neither leak stale reports nor lose entries.

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

## Acceptance

- Tests cover empty and populated lists, initial-scan races, concurrent local
  changes, completed sessions, invalid metadata, and oversized reports.
- Sessions created or changed while offline appear after reconnect; repeated
  requests and connection replacement neither leak stale reports nor lose entries.

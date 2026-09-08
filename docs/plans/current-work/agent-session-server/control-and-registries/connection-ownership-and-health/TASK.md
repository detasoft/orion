# Track Authoritative Connections and Health

Status: todo
Depends on: ../launch-and-reconnect-authentication/TASK.md

Maintain one authoritative authenticated connection per logical agent and
derive current availability from that connection and its heartbeats.

## Scope

- Atomically replace the active connection after successful authentication;
  obsolete connections must receive no further work or update current state.
- Ignore stale disconnects, heartbeats, and callbacks from replaced connections.
  Generation revocation must also invalidate a still-open old connection.
- Validate heartbeat and agent-status identities against their authenticated
  context. Use server observation time for liveness and last-seen metadata.
- Apply the agreed heartbeat deadline and reconnect-token renewal policy while
  retaining the current launch during the configured offline recovery window.
- Expose active-connection lookup and launch-specific availability through the
  existing ownership model; keep physical connection state transient.

## Acceptance

- Tests cover initial online state, heartbeat timeout, recovery, connection
  takeover during a partition, and immediate generation revocation.
- Late callbacks from an old connection cannot mark its replacement offline,
  renew obsolete credentials, or overwrite the replacement's metadata.

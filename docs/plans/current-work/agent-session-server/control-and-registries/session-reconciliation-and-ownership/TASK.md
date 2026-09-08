# Reconcile Sessions and Enforce Ownership

Status: todo
Depends on: ../connection-ownership-and-health/TASK.md
Replication consumer: ../../session-replication/TASK.md

Rebuild the server's view of an agent's sessions after every authenticated
connection while preserving durable session ownership and history.

## Scope

- Request `SESSION_LIST` after each connection and reconcile the reported
  sessions with durable agent-to-session ownership and lightweight metadata.
- Include completed and degraded sessions, preserve known history when a
  session is missing from a report, and do not infer process exit from absence.
- Reject claims for sessions belonging to another agent and ignore reports
  from obsolete connections. Define handling of newly discovered session IDs.
- Provide the authenticated agent/session ownership checks required by
  replication and command routing without depending on either implementation.
- Preserve authoritative durable process outcomes when they are available;
  transient reports must not overwrite them. Journal-event integration follows
  in the replication and command tasks.

## Acceptance

- Tests cover fresh discovery, repeated reports, reconnect, server restart,
  completed and missing sessions, conflicting ownership, and connection takeover.
- Ownership checks reject unknown or foreign sessions; real replication-stream
  binding is verified by session-replication when its endpoint is implemented.

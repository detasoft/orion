# Reconcile Sessions and Enforce Ownership

Status: todo
Depends on: connection ownership and health completed in `afc52028`, `3481b970`,
`c0f8a0ad`, and `068877e6`.
Replication consumer: ../02_session-replication.md

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

## Design

### Durable session registry

Add one filesystem-backed session registry beside the agent registry. A session
record is keyed by `SessionId` and contains its immutable owning `AgentId`, the
latest reported lightweight metadata, and an optional authoritative process
outcome. The registry, rather than a connection or reconciler, is the one owner
of durable session identity and metadata.

Store each session independently so the number and size of session records do
not expand an agent record or make unrelated agent updates contend on one
publication. Use bounded records, collision-safe file names, a root ownership
lock, atomic replacement, and directory synchronization consistently with the
existing durable registries. Opening the registry reconstructs ownership from
disk and rejects corrupt or duplicate durable identity.

The registry exposes only the operations required by current consumers:

- reconcile one complete report for an authenticated agent;
- record an authoritative process outcome when a later command or journal
  consumer obtains one;
- read one record and list the sessions owned by an agent;
- check that a known session belongs to an authenticated agent.

Unknown sessions fail ownership checks. The first complete report that contains
a new session atomically assigns it to the reporting agent. A report containing
any session already owned by another agent is rejected before any record is
changed, so one report cannot be partially applied.

### Reconciliation rules

For a new or same-owner session, copy the reported state, retained journal
range, and diagnostic detail into durable metadata. Replaying the same report
is idempotent. A same-owner reconnect may update that metadata, including
reporting an already completed or degraded session.

Sessions absent from a report remain unchanged. Absence does not change state,
clear metadata, transfer ownership, or imply process exit.

An authoritative process outcome is a separately recorded terminal fact. Once
present, reconciliation may refresh the reported journal range and detail but
must not replace that outcome with a transient reported state. This provides
the ownership and precedence contract needed by later command and replication
work without implementing either consumer here.

### Authenticated control flow

Add one authenticated-session publisher that owns session reconciliation. When
published, it sends `REQUEST_SESSION_LIST` through that connection. It accepts
`SESSION_LIST` messages and reconciles them under the authenticated `AgentId`;
unrelated messages pass to its configured downstream session.

Keep connection authority in `AuthenticatedAgentConnections`. Its existing
callback fence ensures that a taken-over connection cannot deliver a late
session report. Reconciliation does not add another connection registry or
duplicate generation state.

A request-send failure closes the affected connection. A malformed ownership
claim or durable registry failure also closes that connection without applying
a partial report. Closing a connection never removes or rewrites durable
session records.

## Implementation plan

1. Add the bounded durable session record, codec, filesystem registry, and
   ownership queries. Cover discovery, idempotent updates, restart recovery,
   missing sessions, foreign ownership, and authoritative-outcome precedence.
2. Add the authenticated reconciliation publisher, request delivery, report
   handling, and failure behavior. Compose it with the existing connection
   owner and cover reconnect plus connection takeover.
3. Remove this completed leaf and update its parent state after the full
   acceptance boundary is verified.

## Acceptance

- Tests cover fresh discovery, repeated reports, reconnect, server restart,
  completed and missing sessions, conflicting ownership, and connection takeover.
- Ownership checks reject unknown or foreign sessions; real replication-stream
  binding is verified by session-replication when its endpoint is implemented.

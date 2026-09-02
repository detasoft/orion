# Verify the Server-Side MVP Acceptance Flow

Status: todo
Depends on: all sibling central agent session server tasks

Prove the central server can manage an AgentD connection and preserve a
complete usable session history across transport and process failures.

## Scope

- Connect an authenticated AgentD, complete `HELLO`/`WELCOME`, reconcile its
  sessions, start a session, route commands, and replay it in the web terminal.
- Replicate a new journal from its first available event, interrupt the
  connection, grow the local journal, and resume after the exact committed ID.
- Verify resent identical records create no duplicates and conflicting bytes
  for one `(sessionId, eventId)` fail as protocol or storage corruption.
- Restart the server and verify cursors come from durable storage; exercise a
  retention gap and continue from the first event still available on AgentD.
- Catch up an exited session, synchronize multiple sessions fairly under a
  large backlog, and verify live consumers never observe an unpersisted event.
- Document MVP configuration and explicitly deferred cleanup, semantic
  projections, production object storage, and clustered-server behavior.

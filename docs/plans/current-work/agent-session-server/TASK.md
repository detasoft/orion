# Build the Central Agent Session Server

Status: todo
Journal contract: ../../2026-09-02-session-journal-cbor-sequence.md
Agent counterpart: ../agentd/TASK.md

Accept outbound AgentD connections, retain the replicated part of every session
journal, route commands, and expose durable history and live events to clients.

## Scope

- Keep persistent identity in `AgentId`, `AgentInstanceId`, `SessionId`,
  `EventId`, and `CommandId`; treat HTTP/2 connections and streams as disposable.
- Derive every resume cursor from committed server storage and make duplicate
  replication harmless while rejecting conflicting bytes for one event ID.
- Preserve unknown journal records without transport-layer interpretation and
  persist every event before making it visible to live consumers.
- Reconcile agents and sessions after reconnect or server restart, including
  connection takeover, completed-session catch-up, and retained-history gaps.
- Defer semantic projections, local-session deletion, clustered deployment,
  and production object storage until the raw journal path is complete.

## Child Tasks

- [x] [Define the server Agent protocol contracts](protocol-contracts/TASK.md)
- [ ] [Implement durable session journal storage](journal-storage/TASK.md)
- [ ] [Replicate session journals over HTTP/2](session-replication/TASK.md)
- [ ] [Implement control streams and registries](control-and-registries/TASK.md)
- [ ] [Route commands to connected agents](command-service/TASK.md)
- [ ] [Expose historical and live session events](live-event-api/TASK.md)
- [ ] [Connect the first web terminal consumer](web-terminal/TASK.md)
- [ ] [Verify the server-side MVP acceptance flow](release-and-acceptance/TASK.md)

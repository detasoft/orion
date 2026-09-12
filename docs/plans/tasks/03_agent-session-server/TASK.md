# Build the Central Agent Session Server

Status: todo
Journal contract: [native session-host contract](../05_native-session-host/TASK.md#session-journal-cbor-sequence-format)
Agent counterpart: ../04_agentd/TASK.md

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
  connection takeover and completed-session catch-up. Do not infer lost records
  from numeric EventId jumps; verifiable continuity is deferred.
- Defer semantic projections, local-session deletion, clustered deployment,
  and production object storage until the raw journal path is complete.

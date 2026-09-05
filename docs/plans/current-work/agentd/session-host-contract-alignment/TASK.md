# Align AgentD with the Stabilized Session-Host Contract

Status: todo
Parent: ../TASK.md
Depends on:
[unified AgentD native controls](../native-control-contract/TASK.md),
[AgentD termination control cleanup](../terminate-control-contract/TASK.md)
Session-host review: ../../../../../session-host/MODULE_REVIEW.md

Bring every remaining AgentD session-host integration path to the final native
control, journal, metadata, and lifecycle contracts after the focused control
model changes are complete.

## Scope

- Audit AgentD launch, discovery, control transport, journal projection,
  recovery, retention acknowledgement, and status handling against the current
  session-host protocol and compatibility fixtures.
- Treat `RECEIVED` as transient admission only. Observe operation completion
  through `COMMAND_RESULT` and preserve the documented unknown/partial-effect
  semantics after ambiguous delivery or a missing result record.
- Use the single schema-2 operation sequence and exact opaque command envelope
  without restoring a duplicate native command identity, result ledger, or
  schema-1 operation fallback.
- Send `ACK_JOURNAL` through the same schema-2 operation contract and advance
  it only from a server-durable journal prefix.
- Consume the four-byte `TERMINATE` effect and effective sandbox status without
  adding AgentD-owned process-tree or host-shutdown policy.
- Align start-outcome and journal-failure handling with the decisions recorded
  in `session-host/MODULE_REVIEW.md`; do not invent an AgentD-local recovery
  interpretation for a native journal gap or missing `PROCESS_STARTED`.
- Remove superseded Java branches, adapters, aliases, retry assumptions, and
  legacy-only tests once every real consumer uses the canonical path.

## Acceptance

- AgentD can launch and rediscover a real session host, issue every established
  operation, reconnect after an uncertain control exchange, and distinguish
  admission from the durable result observed in the journal.
- Journal resume and `ACK_JOURNAL` use one server-confirmed durable prefix and
  preserve retention-gap reporting.
- Start success, pre-exec start failure, missing operation result, partial
  effect, graceful terminate, force terminate, and sandbox status match the
  final session-host contract.
- Shared protocol fixtures and real-host integration tests cover unsigned
  sequences above `i64::MAX`, unknown envelope fields, reconnect, and ACK.
- No obsolete schema-1 operation path, duplicate command identity, private
  durable command cursor, or AgentD-owned host termination coordinator remains.

## Boundary

This task owns AgentD conformance with the established native host contracts
and removal of superseded AgentD paths. It does not change the session-host
wire or journal formats, implement server command orchestration, or add local
terminal UI behavior.

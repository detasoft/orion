# Review the Agent Session Control Plane

Status: todo
Depends on:
[AgentD MVP acceptance](../../current-work/agentd/release-and-acceptance/TASK.md),
[central agent session server MVP acceptance](../../current-work/agent-session-server/release-and-acceptance/TASK.md)
Required skill: `architecture-simplifier`

Perform a read-only end-to-end architecture review of `agentd` and the central
agent session server after both sides of the current MVP are complete.

## Scope

- Trace connection, agent instance, local session, server session, and stream
  ownership across disconnect, reconnect, replacement, shutdown, and recovery.
- Identify the source of truth for session lifecycle, journal availability,
  durable cursors, gaps, acknowledgements, and replay progress.
- Verify that command correlation, idempotency, retry identity, journaled
  effects, and result delivery form one coherent model.
- Inspect registries, queues, state machines, snapshots, reconnect
  coordinators, timers, and callbacks for duplicated state or unnecessary
  coordination.
- Review backpressure and failure isolation between control, heartbeat, and
  per-session journal streams.
- Reuse the existing `agent-protocol` and `session-host` module reviews;
  inspect those modules only where their contracts cross this control plane.

## Deliverables

- Save a dated evidence-backed report under `docs/reviews/` with one proposed
  ownership, identity, lifecycle, and cursor model.
- Record which protocol facts are authoritative, derived, cached, or merely
  observational, including behavior after restart and reconnect.
- Create separate task-tree nodes for accepted changes; do not modify
  production code during the review.

## Completion Criteria

- The review follows representative launch, command, journal sync, reconnect,
  gap, exit, and failure paths through both processes and durable storage.
- Findings distinguish verified implementation behavior from protocol intent
  and explicitly identify any contract changes.
- Remaining cross-module duplication and coordination have named owners or
  focused follow-up tasks.

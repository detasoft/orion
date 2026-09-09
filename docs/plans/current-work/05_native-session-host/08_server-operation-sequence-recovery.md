# Resolve SERVER Operation Sequence Recovery

Status: todo
Depends on: [source-aware controls](07_source-aware-controls.md)
Coordinates with: [AgentD journal sync](../04_agentd/02_journal-sync.md) and
[server command orchestration](../04_agentd/04_command-orchestration.md)

Define how a stateless AgentD can safely allocate the next `SERVER` operation
sequence after reconnect or restart when a prior host admission may have no
journaled `COMMAND_RESULT`.

## Scope

- Identify the authoritative fact that covers accepted `SERVER` operations even
  when their result append is pending or permanently missing; recorded journal
  maxima alone cannot authorize allocation.
- Compare the smallest viable ownership and transport choices, preserving full
  unsigned `u64` values and source separation without adding a second command
  ledger.
- Combine the live-host observation with the server-durable prefix and local
  journal suffix without treating missing results as permission to replay.
- Keep `MANUAL` sequences outside server recovery and keep `ACK_JOURNAL` in the
  same `SERVER` allocation order as other server operations.
- If the authoritative recovery fact is unavailable or inconsistent, pause
  only that session and report an unresolved command state; never guess, reuse,
  or persist a replacement AgentD counter.
- Update the command-orchestration and journal-sync designs/plans to consume one
  canonical recovery rule before either implementation resumes.

## Acceptance

- After an admitted operation loses its result, a replacement AgentD either
  allocates from an authoritative value without replaying the uncertain command
  or explicitly refuses further delivery for that session.
- Recovery remains correct with retained-prefix gaps, interleaved `MANUAL`
  records, and unsigned sequence values above `i64::MAX`.
- Missing recovery authority cannot authorize another server operation.
- No AgentD durable sequence state, host durable intent ledger, compatibility
  path, or second allocator is introduced.

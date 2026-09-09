# Add Explicit Sources to Session Control Commands

Status: todo
Depends on: unified AgentD native controls (completed in `c298ad34`)
Blocks: [SERVER sequence recovery](08_server-operation-sequence-recovery.md) and
[local terminal](../04_agentd/07_local-terminal.md)
Related: [server command orchestration](../04_agentd/04_command-orchestration.md)
Plan: ../../2026-09-09-source-aware-session-controls.md

- Owner: codex, session source-aware-controls-701c, branch `codex/source-aware-controls-701c`, worktree
  `.worktrees/source-aware-controls-701c`, resumed 2026-09-09 23:24 Europe/Amsterdam.

Allow AgentD and a manual client to control the same live session concurrently,
including direct manual access while AgentD is unavailable. Mark every command
with its explicit source while keeping one wire contract, execution path, and
journal.

## Scope

- Replace the current control-operation payload with one canonical layout that
  carries `source: SERVER | MANUAL` beside `operationSequence`. Preserve the
  full unsigned `u64` range; do not encode source in reserved bits or assign
  priority by numeric range.
- Keep `SERVER` ordering and deduplication scoped to the host session. Admit
  operations through an in-memory sequence high-water mark and preserve exact
  opaque server command envelopes in `COMMAND_RESULT`. Attempt durable result
  append after each effect; missing results remain unknown and do not authorize
  replay.
- Give `MANUAL` no high-water mark, ordering state, deduplication, or durable
  retry identity. Its `operationSequence` is only a live response-correlation
  value: every delivered valid frame executes, including another frame with the
  same source and sequence on the same or a different connection. A manual
  client sends each intended effect once and does not retry an uncertain
  delivery after disconnect.
- Admit both sources into the same existing serialized PTY/process-control
  execution path after source-specific sequence validation. Journal `eventId`
  remains the common order; disconnect creates no `MANUAL` cleanup or retained
  source state. Preserve the existing termination ordering exception.
- Make all existing operations available to both sources: `INPUT`, `RESIZE`,
  `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL`. Source changes sequence semantics,
  not the operation allowlist. The first local-terminal client may choose to
  emit only input/resize and no ACK, but that is client policy rather than a
  protocol restriction; a delivered manual ACK has the normal retention
  effect.
- Record source and operation sequence in `COMMAND_RESULT`, together with the
  exact accepted source envelope: the opaque server command envelope for
  `SERVER`, and the exact received control-operation payload for `MANUAL`.
  Preserve outcome and detail. Manual events remain part of replicated session
  history, but later server recovery ignores their sequences.
- Preserve the existing delivery boundary: `RECEIVED` confirms admission, not
  durable effect completion. A lost receipt does not cancel an admitted effect;
  effect failures produce `COMMAND_RESULT`, and result-append failures leave the
  effect outcome unknown rather than authorizing replay.
- Update Rust controls, Java codecs/models, protocol fixtures, journal readers,
  and every existing consumer together. The replacement layout is the only
  current layout: remove the old schema constants, readers, writers, fixtures,
  documentation, and single-sequence path. Do not add migration, fallback,
  compatibility shims, or parallel legacy implementations.
- Reconcile the local-terminal and command-orchestration designs/plans with
  these contracts, including one-shot manual delivery and exclusion of manual
  sequences from server recovery.

## Acceptance

- Two live control connections can interleave `SERVER:42`, `MANUAL:1`,
  `SERVER:43`, and `MANUAL:2` without collisions or false stale rejections.
  Effects and journal records retain one host-assigned order.
- Repeating a `SERVER` sequence is rejected by the session high-water mark;
  repeating a `MANUAL` sequence executes again, including across simultaneous
  or reconnected control clients. No manual high-water or duplicate-removal
  state exists.
- `INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL` use the same
  validation, execution, and result-journaling path for both sources. Tests
  retain meaningful coverage of the existing termination ordering exception.
- Each result attributes its source and full unsigned sequence, including values
  above `i64::MAX`, and preserves the exact source envelope/control payload.
- Real-host and Java/Rust fixture tests cover interleaved sources, repeated
  manual delivery, server stale rejection, all operation types, result
  attribution, effect failure, lost receipt, and result-append failure using
  only the replacement wire and journal layouts.

## Boundary

This task owns source-aware control and journal contracts plus their existing
consumers. Recovering the next `SERVER` sequence after AgentD reconnect belongs
to `08_server-operation-sequence-recovery.md`. Terminal UI construction,
process ownership, termination coordination, and physical retention remain
separate.

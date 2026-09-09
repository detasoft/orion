# Add Explicit Sources to Session Control Commands

Status: todo
Depends on: unified AgentD native controls (completed in `c298ad34`)
Blocks: [SERVER sequence recovery](08_server-operation-sequence-recovery.md) and
[local terminal](../04_agentd/07_local-terminal.md)
Related: [server command orchestration](../04_agentd/04_command-orchestration.md)

Allow AgentD and a manual client to control the same live session concurrently,
including direct manual access while AgentD is unavailable. Separate command
sequences by an explicit source while keeping one execution path and journal.

## Scope

- Add `source: SERVER | MANUAL` to the local control-command wrapper beside
  `operationSequence`. Preserve the full unsigned `u64` range; do not encode
  source in reserved bits or assign priority by numeric range.
- Keep `SERVER` ordering and deduplication scoped to the host session. Admit
  operations through an in-memory sequence high-water mark and preserve exact
  opaque server command envelopes in `COMMAND_RESULT`. Attempt durable result
  append after each effect; missing results remain unknown and do not authorize
  replay.
- Scope `MANUAL` ordering and retry identity to the control connection. A new
  connection may start its own sequence; do not automatically replay commands
  whose delivery became uncertain on disconnect. Manual commands need no
  fabricated server envelope or private durable recovery cursor.
- Bound manual bookkeeping independently of server acknowledgement and release
  it after the connection's in-flight work settles. Manual history must not
  affect server journal acknowledgement state.
- Use one admission/execution implementation for both sources, with explicit
  source-specific bookkeeping. Serialize effects through the existing PTY and
  process-control path; journal `eventId` remains the common event order.
- Record source in journal command records that carry an operation sequence.
  Keep `MANUAL` records distinguishable so later server recovery can ignore
  their sequences. Manual events remain part of replicated session history.
- Keep the manual reader outside replication acknowledgement: it reads and
  follows the journal but never sends `ACK_JOURNAL` or pins retention. Report a
  retention gap and continue from the available suffix without guessing server
  sequence state. Only server-confirmed durable replication authorizes ACK.
- Update Rust controls, Java codecs/models, protocol fixtures, journal readers,
  and existing consumers together. Explicitly version any changed wire or
  persisted layout and remove the replaced single-sequence path; do not retain
  compatibility shims or parallel legacy implementations.
- Reconcile the local-terminal and command-orchestration designs/plans with
  these contracts, including removal of manual `--ack-journal` and server
  history from manual numbering.

## Acceptance

- Two live control connections can interleave `SERVER:42`, `MANUAL:1`,
  `SERVER:43`, and `MANUAL:2` without collisions or false stale rejections.
  Effects and journal records retain one host-assigned order.
- Same-source retries/conflicts remain correct; a manual reconnect can reuse a
  number without repeating uncertain prior input or conflicting with the old
  connection's in-flight operation. Server admission retains its session scope.
- Manual input works without AgentD and without ACK; bounded bookkeeping does
  not depend on retained server results. Manual reading does not advance the
  retention watermark, and an observer gap does not disable independent manual
  numbering.
- Real-host tests cover simultaneous Java clients, reconnects, interleaved
  input/resize, result attribution, and unchanged server ACK durability.

## Boundary

This task owns source-aware control and journal contracts plus their existing
consumers. Recovering the next `SERVER` sequence after AgentD reconnect belongs
to `08_server-operation-sequence-recovery.md`. Terminal UI construction,
process ownership, termination coordination, and physical retention remain
separate.

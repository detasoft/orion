# Add Explicit Sources to Session Control Commands

Status: todo
Depends on: [unified AgentD native controls](../../agentd/native-control-contract/TASK.md)
Blocks: [local terminal](../../agentd/local-terminal/TASK.md)
Related: [server command orchestration](../../agentd/command-orchestration/TASK.md)

Allow AgentD and a manual client to control the same live session concurrently,
including direct manual access while AgentD is unavailable. Separate command
sequences by an explicit source while keeping one execution path and journal.

## Scope

- Add `source: SERVER | MANUAL` to the local control-command wrapper beside
  `operationSequence`. Preserve the full unsigned `u64` range; do not encode
  source in reserved bits or assign priority by numeric range.
- Keep `SERVER` ordering and deduplication scoped to the host session across
  AgentD reconnects. Admit operations through an in-memory sequence high-water
  mark and preserve exact opaque server command envelopes in `COMMAND_RESULT`.
  Attempt durable result append after each effect; missing results remain
  unknown and do not authorize replay.
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
  Update server-prefix projection and AgentD suffix recovery to consider only
  `SERVER` sequences and results when restoring server command state. Manual
  events remain part of the replicated session history.
- Keep the manual reader outside replication acknowledgement: it reads and
  follows the journal but never sends `ACK_JOURNAL` or pins retention. Report a
  retention gap and continue from the available suffix without guessing server
  sequence state. Only server-confirmed durable replication authorizes ACK.
- Update Rust controls, Java codecs/models, protocol fixtures, journal readers,
  server projections, and existing consumers together. Explicitly version any
  changed wire/persisted layout and remove the replaced single-sequence path;
  do not retain compatibility shims or parallel legacy implementations.
- Reconcile the local-terminal and command-orchestration designs/plans with
  these contracts, including removal of manual `--ack-journal` and recovery of
  manual numbering from server journal history.

## Acceptance

- Two live control connections can interleave `SERVER:42`, `MANUAL:1`,
  `SERVER:43`, and `MANUAL:2` without collisions or false stale rejections.
  Effects and journal records retain one host-assigned order.
- Same-source retries/conflicts remain correct; a manual reconnect can reuse a
  number without repeating uncertain prior input or conflicting with the old
  connection's in-flight operation. Server retries retain their session scope.
- AgentD recovery obtains the same next server sequence with or without manual
  records in either the server prefix or local suffix, including `u64` values
  above `i64::MAX`. Use the reconnect allocation contract resolved by command
  orchestration; recorded maxima alone do not expose pending or missing results.
- Manual input works without AgentD and without ACK; bounded bookkeeping does
  not depend on retained server results. Manual reading does not advance the
  retention watermark, and an observer gap does not disable independent manual
  numbering.
- Real-host tests cover simultaneous Java clients, reconnects, interleaved
  input/resize, result attribution, and unchanged server ACK durability.

## Boundary

This task owns source-aware control and journal contracts plus their existing
consumers. Terminal UI construction belongs to the local-terminal task; process
ownership, termination coordination, and physical retention remain separate.

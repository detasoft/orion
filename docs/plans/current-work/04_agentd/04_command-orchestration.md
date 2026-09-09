# Route Server Session Commands

Status: todo
Design: ../../2026-09-03-agentd-command-orchestration-design.md
Plan: ../../2026-09-03-agentd-command-orchestration.md
Depends on: completed AgentD HTTP/2 transport, session discovery, and session
runtime/control; completed journal-reader (02e74a3a); 02_journal-sync.md;
completed native-control-contract (c298ad34); [source-aware controls](../05_native-session-host/07_source-aware-controls.md);
[SERVER sequence recovery](../05_native-session-host/08_server-operation-sequence-recovery.md); and the
[native control-journal idempotency design](../../2026-09-03-native-control-journal-idempotency-design.md)

- [ ] Route server session commands.
  - Owner: codex, session command-orchestration-d8e4, paused 2026-09-03 19:51 Europe/Amsterdam.
  - Next: Resume after journal sync, source-aware controls, and the canonical
    `SERVER` sequence recovery contract are integrated; then rebase and update
    the stale implementation plan to the resulting APIs.

Validate and route server commands while deriving durable outcomes exclusively
from each session journal.

## Scope

- Dispatch `START_SESSION`, `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` by
  command and session identity.
- Validate policy, lifecycle state, payload bounds, terminal dimensions, and
  runtime or workspace selection before local delivery.
- Preserve each exact server CBOR command envelope and assign one recovered,
  monotonic `SERVER` operation sequence across `INPUT`, `RESIZE`, `SIGNAL`, and
  `TERMINATE`; ignore `MANUAL` sequences during server recovery.
- Use bounded serial execution per session with cross-session concurrency;
  recover sequence and lifecycle observations by scanning the local journal to
  its tail before accepting commands.
- Complete commands from journaled host results and lifecycle records. Treat
  a missing result as unknown, `PROCESS_EXITED` as the only authoritative exit,
  and emit no separate `SESSION_STARTED` or successful direct `COMMAND_RESULT`.
- Represent a pre-journal start failure as a bounded in-memory failure-only
  session journal; persist no AgentD cursor or failure file.
- Test ordering, duplicates, recovery and crash windows, invalid or missing
  state, host reconnect, failure-only starts, and journal-authoritative exit.

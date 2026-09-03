# Route Server Session Commands

Status: todo
Design: ../../../2026-09-03-agentd-command-orchestration-design.md
Plan: ../../../2026-09-03-agentd-command-orchestration.md
Depends on: completed AgentD HTTP/2 transport, session discovery, and session
runtime/control; ../journal-reader/TASK.md; ../journal-sync/TASK.md; and
../../native-session-host/control-journal-idempotency/TASK.md

- [ ] Route server session commands.
  - Owner: codex, session command-orchestration-d8e4, paused 2026-09-03 19:51 Europe/Amsterdam.
  - Next: Resume after journal-reader, journal-sync, native control-journal-idempotency, and the native
    start-outcome contract are integrated.

Validate and route server commands while deriving durable outcomes exclusively
from each session journal.

## Scope

- Dispatch `START_SESSION`, `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE` by
  command and session identity.
- Validate policy, lifecycle state, payload bounds, terminal dimensions, and
  runtime or workspace selection before local delivery.
- Preserve each exact server CBOR command envelope and assign one recovered,
  monotonic `operationSequence` across `INPUT`, `RESIZE`, `SIGNAL`, and
  `TERMINATE`.
- Use bounded serial execution per session with cross-session concurrency;
  recover sequence and lifecycle observations by scanning the local journal to
  its tail before accepting commands.
- Complete commands from journaled host intent and result records. Treat
  `PROCESS_EXITED` as the only authoritative exit and emit no separate
  `SESSION_STARTED` or successful direct `COMMAND_RESULT`.
- Represent a pre-journal start failure as a bounded in-memory failure-only
  session journal; persist no AgentD cursor or failure file.
- Test ordering, duplicates, recovery and crash windows, invalid or missing
  state, host reconnect, failure-only starts, and journal-authoritative exit.

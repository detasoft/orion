# Route Server Session Commands

Status: todo
Depends on: completed AgentD HTTP/2 transport, session discovery, runtime and
control; 02_journal-sync.md; completed source-aware native controls
(`5005ae2a`); and the native control-journal contract

Validate and route server commands while deriving durable outcomes exclusively
from each session journal.

## Ownership and Boundaries

- The server owns durable command identity/state and the authoritative journal
  cursor.
- `session-host` owns effect execution, SERVER sequence admission, the process
  tree, and durable command-result records.
- Journal sync owns journal resume, journal upload, and EventId-only retention
  acknowledgement independently from command sequencing.
- This leaf owns command validation, bounded per-session scheduling, exact
  command-envelope delivery with the server-assigned operation sequence,
  connection fencing, start routing, and live observation needed to report
  transient command progress.
- AgentD persists no command ledger, operation counter, journal cursor, start
  failure file, or host lifecycle fact.

Do not duplicate journal scanning, retention acknowledgement, server sequence
allocation, server journal projection, or host operation idempotency here.

## Command Model

`START_SESSION` is keyed by the server-issued unique SessionId and CommandId and
has no native operation sequence. Established-session `INPUT`, `RESIZE`,
`SIGNAL`, and `TERMINATE` retain the stable per-session operation sequence
durably assigned by the server.

Preserve the exact inbound CBOR command item, including future fields, beside
its typed view. When a command reaches the head of its session lane, validate
its current local preconditions and make one native delivery attempt with the
server-assigned sequence, typed effect, and unchanged envelope. Never
reconstruct the envelope by re-encoding the typed message.

An empty native `RECEIVED` is transient admission evidence only. A rejection,
timeout, queue-capacity failure, missing session, or connection failure may be
reported as transient delivery state, but successful command completion comes
only from a durably replicated `COMMAND_RESULT`. AgentD never retries a native
delivery after rejection, timeout, ambiguous delivery, reconnect, or a missing
result; a missing result remains unknown.

## Scheduling and Isolation

- Commands for one session use a bounded FIFO lane and execute serially.
- Different sessions may progress concurrently through a shared executor.
- A lane does not drain until command orchestration fences stale native
  connections. Reconnect closes the old connection and gates new delivery until
  the new claim succeeds. The claim carries no journal-derived sequence floor and
  does not recover an AgentD allocator.
- A full, failed, corrupt, or unreachable session lane does not block another
  lane, heartbeat, or journal upload.
- Shutdown stops admission and boundedly drains or cancels AgentD work without
  sending terminate to any host.

## Session Start

Validate runtime, workspace, policy, environment, command, dimensions, and
session collision before calling `SessionRuntime`. Once a host journal exists,
start success or failure and eventual process completion come only from journal
records. `PROCESS_EXITED` is the sole authoritative exit observation.

If launch fails before any journal exists, expose one bounded in-memory
failure-only journal containing `SESSION_START_FAILED` with EventId 1. Register
it with the normal replication path until the server durably commits it, then
discard it. Do not write a cursor or failure file. Diagnostic text remains
bounded; release safety still depends on the separate diagnostic-redaction
task.

## Implementation Plan

1. Preserve exact encoded inbound control items through protocol decoding and
   AgentD transport while keeping typed session-stream delivery unchanged.
2. Simplify the native server-control claim to a sequence-independent connection
   fence. It neither receives a journal-derived floor nor returns allocator state.
3. Add bounded per-session FIFO lanes with cross-session concurrency, capacity
   results, recovery gating, reconnect fencing, isolated failure, and safe
   close behavior.
4. Route established-session commands once with their server-assigned sequences.
   Cover exact envelopes, monotonic server allocation, validation, rejection,
   timeout, ambiguous delivery, and no AgentD retry.
5. Route `START_SESSION` through the existing runtime. Cover collision,
   journaled start outcomes, pre-journal failure-only streams, reconnect until
   durable commit, and diagnostic bounds.
6. Observe journaled results and lifecycle facts without creating another
   reader or cursor. Correlate by source plus sequence because MANUAL sequences
   may repeat and result order may differ from admission order.
7. Connect post-handshake server messages, lifecycle, discovery, journal sync,
   runtime, and command lanes through the existing AgentD composition.
8. Verify same-session ordering, cross-session progress, overload, reconnect,
   crash windows, missing results, host loss, process exit, shutdown, and real
   native-host interoperability before full Maven verification.

## Acceptance

- Every supported server command is validated and routed to the intended
  session; same-session order and cross-session independence are preserved.
- Established-session commands retain sequences from the durable server
  allocator; journal retention acknowledgements remain outside command state.
- Reconnect fencing neither derives, allocates, nor synchronizes command
  sequences in AgentD.
- Exact server envelopes reach native journal results unchanged, and durable
  completion is derived only from replicated journal evidence.
- Missing results, rejection, ambiguous delivery, stale connections, and
  reconnect never cause AgentD to repeat a native command.
- Pre-journal start failure uses only the bounded in-memory journal path, and
  AgentD shutdown never owns or terminates a session host.

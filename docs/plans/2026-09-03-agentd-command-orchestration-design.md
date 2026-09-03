# AgentD Command Orchestration Design

## Status

Approved on 2026-09-03. This design supersedes the command-result and
`SESSION_STARTED` portions of the broader 2026-09-02 AgentD plan. It does not
change the server-authoritative journal cursor or make AgentD a durable state
owner.

## Goals and Boundaries

The central server generates and durably persists every unique, single-use
`SessionId` and `CommandId`. Reusing either identifier for different work is a
protocol error. AgentD validates and routes commands but remains stateless: it
does not persist command results, operation counters, journal cursors, or start
failure files. `session-host` owns local execution and its durable journal. The
server owns durable command state and the authoritative journal replication
cursor.

Commands for one session execute through a bounded serial lane. Commands for
different sessions may execute concurrently. A busy, corrupt, or unreachable
session cannot block another session, control-stream liveness, or journal
replication.

`START_SESSION` is a session-creation operation keyed by the server-issued
session and command identities. The four established-session controls,
`INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE`, share one monotonically increasing
per-session `operationSequence` and one deduplication/ordering contract.

## Distinct Ordering Values

Three similarly named values have different owners and must not be converted
or substituted for one another:

- `eventId` is the unsigned, strictly increasing order of durable records in
  one session journal. The server replication cursor is an `eventId`.
- `operationSequence` is the order AgentD assigns to accepted local control
  attempts for one session. It appears in command intent and result records;
  it is not a journal cursor and gaps are valid.
- metadata `latestTimestamp` or similarly named latest-event fields are
  observational snapshots only. They are neither an `eventId` authority nor
  an `operationSequence` allocator.

`ACK_JOURNAL` carries a server-durably-committed journal `eventId` watermark to
the host. It is a non-journaled, idempotent retention-control command. AgentD
never treats the host retention watermark as replication authority and never
persists it as an AgentD cursor.

## Considered Approaches

### Minimal direct-result router

AgentD could serialize current v1 commands, return `COMMAND_RESULT` directly,
and retry only operations that look safe. This is small, but a lost result or
AgentD restart destroys the evidence needed to distinguish an accepted effect
from an unexecuted command. It cannot safely replay signals, ordered resizes,
or termination and does not satisfy restart recovery.

### AgentD-persisted command ledger

AgentD could persist command IDs, counters, start fingerprints, and results
under its state directory. This recovers local results, but creates a second
durable command authority beside the server and a second execution authority
beside the host journal. It also adds crash consistency, retention, and
takeover coordination that the server-launched stateless model deliberately
avoids.

### Journal-authoritative stateless router

The selected approach records command intent and outcome in the host journal,
lets the server complete commands only from replicated journal records, and
reconstructs AgentD's next operation sequence from server and local journal
facts. This requires coordinated protocol changes, but it gives each durable
fact one owner and contains ambiguity without blind retries.

## Recovery and Sequence Allocation

After AgentD connects or restarts, the server supplies two facts for each
session:

1. the highest `operationSequence` covered by its durably acknowledged journal
   prefix; and
2. its authoritative committed journal `eventId` cursor.

AgentD independently scans the local journal strictly after that cursor through
the current tail. This scan is not an HTTP/2 upload and does not wait for the
replication pump. It finds the maximum unacknowledged `operationSequence`,
reconstructs command intent/result observations, and observes lifecycle
records. Once the scan reaches the tail, the next sequence is:

```text
max(server acknowledged operationSequence,
    local suffix maximum operationSequence) + 1
```

An empty session starts at sequence `1`. Commands that arrive during recovery
remain in the bounded session lane and do not start until the scan reaches the
tail. Journal backlog upload may continue concurrently after command execution
is enabled. New journal records are coordinated with the tail handoff so the
scan cannot miss an operation between its final read and live observation.

If the server cursor is below the retained local floor, or the suffix is
corrupt, AgentD pauses only that session and reports the integrity failure. It
must not guess a sequence. With correct `ACK_JOURNAL` retention, removed records
are already represented by the server's acknowledged sequence prefix.

## Exact Command Envelope

AgentD validates a typed view of each server command while retaining the exact
CBOR item received from the server, including identifiers and future fields.
It must not reconstruct that envelope by re-encoding a Java record. The local
control request carries the allocated `operationSequence` and the unchanged
server envelope so the host can durably record it and compare exact retry
bytes.

The host accepts a new sequence greater than its durable high-water mark; gaps
are valid. Repeating a sequence with the same envelope is a retry. Reusing a
sequence with different bytes, or presenting an unexplained stale sequence,
is rejected without executing it as new work. All four established-session
controls use this contract, including `RESIZE` and `TERMINATE`.

## Established-Session Command Flow

For each command, AgentD performs bounded validation and enqueues the original
envelope in the session lane. When it reaches the head of the lane, AgentD
assigns the next `operationSequence` and sends both to the matching host.

The host then:

1. validates lifecycle, sequence, envelope identity, and command payload;
2. durably appends `COMMAND_ACCEPTED` containing the operation sequence and
   exact command envelope;
3. performs the requested external side effect;
4. durably appends `COMMAND_RESULT` with the same operation sequence and the
   actual succeeded, failed, rejected, or ambiguous outcome; and
5. replies to AgentD with the result record's journal `eventId`.

AgentD may report transient delivery progress or failure on the control stream,
but it sends no successful direct `COMMAND_RESULT`. The server completes a
command only after the journaled result is durably replicated. This keeps a
lost control response from disagreeing with durable history.

A retry after a complete result returns that result's original event ID without
repeating the effect. A crash before durable intent leaves no accepted
operation and is safe to redeliver. A crash after intent but before a durable
result, including a crash during the side effect or after the effect but before
result append, leaves an unmatched intent. Recovery appends
`COMMAND_RESULT(AMBIGUOUS)` and never blindly retries the effect. Later commands
then continue in sequence order.

This conservative outcome applies uniformly even where a particular resize or
signal might appear harmless. It avoids making recovery depend on
operation-specific guesses about external process state.

## Session Start Flow

`START_SESSION` is not assigned an `operationSequence`. The server's unique,
single-use `SessionId` and `CommandId` identify the attempt. AgentD validates
runtime, workspace, policy, environment, command, terminal bounds, and session
collision before calling `SessionRuntime`.

If the host creates any journal, start success or failure comes only from that
journal. The server uses journaled start outcome records; there is no separate
`SESSION_STARTED` message and no successful direct `COMMAND_RESULT`.
`PROCESS_EXITED` is the sole authoritative process-completion record. Control
`STATUS`, endpoint reachability, PID observations, and termination acceptance
remain transient observations and never prove exit.

If launch fails before any host journal ever exists, AgentD constructs a
failure-only CBOR session journal in memory. It contains exactly one record,
with `eventId = 1` and type `SESSION_START_FAILED`, and sends it through the
normal session replication path. AgentD retains it only in bounded memory until
the server durably commits it, then discards it. A disconnect or AgentD crash
does not create a local cursor or failure file; the server may redeliver the
same start identity if it has not committed an outcome.

The diagnostic text is capped at 1 MiB. When larger, it retains the first
64 KiB and last 960 KiB and records the omitted byte count. Secret redaction is
explicitly deferred to
`docs/plans/current-work/agentd/diagnostic-secret-redaction/TASK.md`, which is
required before release. Until that task is complete, this diagnostic path is
not considered safe for production logging or transmission.

## Host Correlation and Deferred Incarnation Identity

An explicit `hostInstanceId` is deferred. The MVP correlates the server-issued
`SessionId`, the control endpoint located under that session directory, and the
manifest/status PID. PID equality is correlation rather than identity proof;
a stale socket plus PID reuse can route to the wrong host incarnation. The
implementation and operational documentation must state this risk and must not
claim cryptographic or durable incarnation fencing.

## Error Handling and Isolation

Protocol bounds and typed fields are validated before lane admission. Server
policy and lifecycle validation should prevent invalid work from reaching
AgentD; AgentD repeats safety-critical local validation before mutation. Host
validation and side-effect failures become journaled command results whenever
a host journal exists.

Connection, timeout, queue-capacity, missing-session, corrupt-journal, and
unreachable-host reports on the control stream are transient delivery facts,
not durable command completion. The server keeps or resolves the durable
command according to journal evidence. AgentD does not automatically replay an
operation after ambiguous local delivery; it scans or asks the host for the
journaled intent/result state.

Each session owns its bounded lane and recovery state. A lane failure rejects
or pauses only that session. Cross-session workers remain available, and
journal upload flow control cannot occupy the command or heartbeat capacity.
AgentD shutdown stops lane admission and boundedly drains or cancels AgentD
work without terminating any host or child process.

## Protocol and Task Migration

Current v1 contracts are insufficient and must evolve with compatibility
fixtures rather than silently changing frozen fields:

- Agent protocol decoding must expose the exact bytes of known command items
  and carry recovery's acknowledged operation sequence.
- Native control must carry `operationSequence` plus the opaque command
  envelope for all four controls and return the durable result event ID.
- The journal contract must allocate and preserve `COMMAND_ACCEPTED`,
  `COMMAND_RESULT`, and `SESSION_START_FAILED` records.
- Journal reading must expose operation and lifecycle observations while still
  preserving unknown records byte-for-byte.
- Server command handling must stop treating direct successful results as
  completion and instead project durable journal results.

The older AgentD plan says no acknowledgement protocol and describes direct
`SESSION_STARTED`/`COMMAND_RESULT`; the current journal-sync task instead
requires durable batch acknowledgement and non-journaled `ACK_JOURNAL`. The
current native control-idempotency task names only input, signal, and append
event, so it must eventually cover resize and terminate too. The server command
task currently allows transient results to appear stronger than this design.
Those prerequisite tasks are not edited here; their implementations must align
with this approved design.

## Verification Design

Protocol and compatibility tests cover exact known-command CBOR preservation,
future tails, all new journal records, all four local-control envelopes,
sequence gaps, exact retries, conflicting reuse, and legacy fixture reading.

Command-orchestrator tests cover every command's validation and happy path;
same-session order; cross-session concurrency; bounded lane overload; unknown,
missing, corrupt, and journal-exited sessions; and independence from heartbeat
and upload backpressure.

Recovery tests provide different server prefix and local suffix maxima, append
while scanning, and large upload backlogs. They prove commands remain paused
until the independent local scan reaches the tail, choose the exact next
sequence, and begin without waiting for upload catch-up.

Crash-window tests cover failure before intent, after intent and before effect,
during or after the effect, after result and before host reply, and after reply
but before server replication. They verify unmatched intent becomes one
durable ambiguous result and dangerous effects are never blindly repeated.

Start tests cover journaled success, journaled failure, collision/retry of the
same identities, failure before journal creation, the one-record in-memory
journal, `eventId = 1`, reconnect until durable server commit, the 1 MiB
first/last diagnostic bound and omission count, and absence of an AgentD cursor
or failure file. Secret cases belong to the separate redaction task.

Lifecycle tests prove `PROCESS_EXITED` alone transitions authoritative process
completion, while control status, socket loss, PID mismatch, and termination
acceptance do not. End-to-end tests combine host reconnect, journal replay,
server cursor recovery, `ACK_JOURNAL`, command result projection, and fair
multi-session progress.

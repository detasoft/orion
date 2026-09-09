# AgentD Command Orchestration Design

> Contract update, 2026-09-07: native-control and recovery passages below are
> historical proposals, not a description of current runtime behavior. The
> [current native contract and Java interface comparison](2026-09-03-native-control-journal-idempotency-design.md)
> define in-memory admission, journaled command results, schema-2 ACK, and
> start-outcome uncertainty.
> Recovery from recorded sequence maxima alone is not established when an
> admitted operation has a pending or missing result. Reconcile affected steps
> with that document before implementing the remaining orchestration work.

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
  attempts for one session. It appears in frame headers and command result
  records; it is not a journal cursor and gaps are valid.
- metadata `latestTimestamp` or similarly named latest-event fields are
  observational snapshots only. They are neither an `eventId` authority nor
  an `operationSequence` allocator.

`ACK_JOURNAL` carries a server-durably-committed journal `eventId` watermark to
the host through a schema-2 operation with its own sequence and opaque envelope.
It produces `COMMAND_RESULT` when the result append succeeds. AgentD never
treats the host retention watermark as replication authority and never
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

The selected approach observes command outcomes in the host journal and lets
the server complete commands only from replicated results. Local delivery and
admission are transient observations. A missing result leaves the effect
unknown, and sequence allocation after reconnect remains unresolved when
recorded history does not expose every admitted operation.

## Recovery and Sequence Allocation

After AgentD connects or restarts, the server supplies two facts for each
session:

1. the highest `operationSequence` covered by its durably acknowledged journal
   prefix; and
2. its authoritative committed journal `eventId` cursor.

AgentD independently scans the local journal strictly after that cursor through
the current tail. This scan is not an HTTP/2 upload and does not wait for the
replication pump. It finds recorded operation sequences, command results, and
lifecycle facts.

The maximum sequence in the server prefix and local suffix is only a lower
bound on the live host's admission high-water mark. An operation may still be
running or may have failed to append its result. Reaching the journal tail
therefore does not prove that the next recorded sequence is safe to allocate.
The orchestration implementation must resolve allocation before enabling
commands after reconnect; it must not infer permission to replay from absent
records or a stale rejection.

Commands awaiting recovery remain in the bounded session lane. Journal backlog
upload may continue independently. A retention gap or corrupt suffix pauses
only the affected session and is reported as an integrity failure. Correct
`ACK_JOURNAL` retention ensures deleted results are represented in the
server-durable prefix, but does not expose unrecorded admissions.

## Exact Command Envelope

AgentD validates a typed view of each server command while retaining the exact
CBOR item received from the server, including identifiers and future fields.
It must not reconstruct that envelope by re-encoding a Java record. The local
control request carries the allocated `operationSequence` and the unchanged
server envelope so the host can preserve it in the result record.

The host accepts a new sequence greater than its in-memory high-water mark;
gaps are valid. Any sequence at or below that mark is stale, including a
byte-identical retry. All established-session operation controls use this
contract, including `RESIZE`, `TERMINATE`, and `ACK_JOURNAL`.

## Established-Session Command Flow

For each command, AgentD performs bounded validation and enqueues the original
envelope in the session lane. When it reaches the head of the lane, AgentD
assigns the next `operationSequence` and sends both to the matching host.

The host then:

1. validates the operation sequence, envelope bounds, and typed effect;
2. advances its in-memory high-water mark and registers the admitted operation;
3. sends an empty `RECEIVED` admission receipt;
4. performs the requested side effect once; and
5. attempts a durable `COMMAND_RESULT` append with the same sequence, exact
   envelope, and succeeded or failed outcome.

Admission rejection returns `RECEIVED` with an error payload. Receipt delivery
failure does not cancel an admitted effect. Ordinary effects share a mutex;
`TERMINATE` bypasses it. Journal results are correlated by sequence, because
physical result order need not match admission order across connections.

AgentD may report transient delivery progress or failure on the control stream,
but sends no successful direct `COMMAND_RESULT`. The server completes a
command only after its journaled result is durably replicated.

A missing result leaves the effect unknown. A failed result can describe a
partial effect. Repeating a sequence is stale and does not return a saved
result; using a new sequence is a new attempt that may repeat a partial effect.
The host logs result-append failure and neither retries the effect nor
synthesizes a recovery result. AgentD must preserve this uncertainty across
reconnects.

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
`docs/plans/current-work/04_agentd/05_diagnostic-secret-redaction.md`, which is
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
admission failures are transient receipts with errors. Admitted side-effect
failures produce journaled command results when the result append succeeds.

Connection, timeout, queue-capacity, missing-session, corrupt-journal, and
unreachable-host reports on the control stream are transient delivery facts,
not durable command completion. The server keeps or resolves the durable
command according to journal evidence. AgentD does not automatically replay an
operation after ambiguous local delivery; it observes journaled results and
keeps a missing result unresolved.

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
- Native control must use the header `operationSequence`, opaque command
  envelope, and typed effect for all schema-2 operations. `RECEIVED` is an
  admission receipt; completion comes from the journal.
- Java journal projection must consume the native `COMMAND_RESULT` and
  `SESSION_START_FAILED` layouts and preserve the exact command envelope.
- Journal reading must expose operation and lifecycle observations while still
  preserving unknown records byte-for-byte.
- Server command handling must stop treating direct successful results as
  completion and instead project durable journal results.

Journal-sync sends schema-2 `ACK_JOURNAL` only from a complete server-durable
prefix and observes its journaled result. It must avoid a feedback loop driven
solely by ACK results. The
[native-control task](current-work/agentd/native-control-contract/TASK.md) and
[alignment task](current-work/04_agentd/03_session-host-contract-alignment/TASK.md)
track the Java changes needed to conform to the current native contract.

## Verification Design

Protocol and compatibility tests cover exact known-command CBOR preservation,
future tails, all new journal records, all four local-control envelopes,
sequence gaps, stale retry rejection, result correlation, and legacy fixture reading.

Command-orchestrator tests cover every command's validation and happy path;
same-session order; cross-session concurrency; bounded lane overload; unknown,
missing, corrupt, and journal-exited sessions; and independence from heartbeat
and upload backpressure.

Recovery tests provide different server prefix and local suffix maxima, append
while scanning, and large upload backlogs. Include an admitted operation whose
result is pending or missing; reaching the tail must not by itself enable a
sequence allocator. Verify the eventual allocation decision separately from
journal upload progress.

Failure-window tests cover disconnect around admission, during or after the
effect, during result append, and before server replication. They preserve
unknown outcomes and verify that stale rejection or missing records never
trigger automatic replay. Persisted results complete commands through normal
replication.

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

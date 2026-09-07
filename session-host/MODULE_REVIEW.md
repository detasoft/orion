# Module Review: `session-host`

Date: 2026-09-07
Status: reviewed in isolation; journal-failure behavior accepted on 2026-09-07; client-count question remains open

## Scope and coverage

This review covers the module's Rust sources, protocol specification, module README, Unix tests, build files,
and current task-tree requirements. It deliberately does not inspect callers or implementations in other
modules.

The review is static and read-only. Maven and Cargo verification were not run because review verification
belongs to the implementation workflow.

Linux is the production platform. macOS provides development support with documented process-tracking limits.
Windows execution remains an unsupported stub and is not treated as a defect in the current module state.

## Current conceptual model

`session-host` owns an interactive child process tree, its PTY, one append-only session journal, a metadata
manifest, and a local control endpoint.

The journal is the durable source of terminal, command, and process history. Metadata is a discovery manifest,
not a journal index or lifecycle replica. The main thread owns process-tree observation and finalization; a PTY
reader drains output; an accept thread starts one worker per control connection. `SharedState` serializes journal
writes and mutable session data. A separate operation mutex serializes ordinary effects, while `TERMINATE`
bypasses it so a blocked `INPUT` cannot prevent process-tree signaling.

Operation replay protection consists only of an in-memory sequence high-water mark. `RECEIVED` confirms
admission, and one later `COMMAND_RESULT` records the observed effect outcome when the journal remains writable.
The server owns grace periods and escalation; the host performs one requested signal delivery and does not
schedule or retry termination.

## Remaining structural findings

### 1. Control connection workers are detached and unbounded

**Finding.** The accept loop creates one detached native thread per control connection without a configured
bound or ownership mechanism.

**Evidence.** `spawn_accept_loop` calls `thread::spawn` for every accepted socket. Session finalization joins the
accept thread and waits for admitted operations, but it does not join connection workers that are idle or blocked
while reading their next frame.

**Requirement.** Blocking Unix sockets make one worker per connection straightforward. The module does not state
how many concurrent control clients one session must support.

**Smallest simplification.** First define a small maximum number of simultaneous control clients. Enforce that
bound at admission and keep the current blocking implementation. A thread pool or async runtime is unnecessary
without a larger verified concurrency requirement.

**Contract and risk.** Excess connections would be rejected or closed. Existing accepted operations and their
finalization guarantee remain unchanged.

**Confidence.** Medium; the missing client-count requirement determines whether this needs implementation.

### 2. Journal maintenance has two commands for one reconciliation operation

**Finding.** `Reconcile` and `ApplyRetention` both update reconciliation inputs and invoke the same maintenance
pass.

**Evidence.** `MaintenanceCommand` carries `Reconcile(activeSegment)` and
`ApplyRetention { activeSegment, acknowledgedEventId }`. `run_maintenance` coalesces both into the same
`reconcile_journal` call.

**Requirement.** Segment rotation and acknowledgement update different inputs at different times, but they do
not require different execution paths.

**Smallest simplification.** Use one reconciliation command carrying the latest active segment and optional
acknowledgement watermark, plus the existing final command. Preserve FIFO coalescing and final synchronization.

**Contract and risk.** No wire or persistence contract changes. Care is required to retain the latest known
acknowledgement when a later segment-only update arrives.

**Confidence.** High.

## Things to try deleting

- Delete the separate `ApplyRetention` maintenance variant after one reconciliation command can preserve both
  latest inputs.
- Avoid introducing a worker registry or async runtime until the required control-client bound is known; a fixed
  admission bound is the smaller model.

## Proposed conceptual model

- One metadata manifest for discovery and one journal as the durable source of session history.
- One operation sequence high-water mark for host-lifetime replay protection, without a result ledger.
- One ordinary-effect serialization path, with explicit `TERMINATE` bypass for blocked PTY input.
- One maintenance reconciliation operation for compression and retention.
- Connection-local delivery failures and non-fatal metadata/journal append failures, preserving the current
  rule that a failed receipt or append does not replay the effect or stop the child.

## Incremental migration path

1. Preserve the accepted journal-failure and post-exec start-outcome behavior documented below.
2. Keep any later process-cleanup or PTY-reader changes covered by the existing failure semantics.
3. Merge the maintenance commands without changing reconciliation or retention behavior.
4. Define and enforce a control-client bound only when the required concurrency is known.

## Do not change

- Keep metadata free of journal position and lifecycle replicas.
- Keep `operationSequence` as the only host replay guard; do not restore a result ledger without a new concrete
  requirement.
- Keep `TERMINATE` independent of the ordinary effect mutex.
- Keep grace periods, escalation, and termination retries outside the host.
- Preserve raw PTY bytes, journal event ordering, durable acknowledgement before retention, and Linux descendant
  identity checks.
- Preserve the documented macOS process-tracking limitations and current Windows unsupported status.

## Accepted journal-failure behavior

Documentation decision, 2026-09-07: the current implementation is the accepted
baseline. The earlier suggestions to terminate on journal loss or require a
durable start record before publishing a live session are superseded; they are
not implementation requirements.

### Output append failure

`copy_pty_output` keeps draining the PTY after a failed append. It discards the
failed chunk, reports the error on stderr, and attempts later chunks. The child
continues running. The local `journal_available` flag suppresses repeated
messages; it is not a STATUS flag or durable state. Because event IDs advance
only for appended records, a reader cannot detect every lost output chunk from
the journal. Durable history contains successfully persisted records, not a
guarantee of complete output after storage failure.

### Start-outcome append failure

`PendingStartOutcome::started` attempts `PROCESS_STARTED` after exec. If the
append fails, it logs the error and returns the journal so startup can publish
a live session. A missing start record therefore does not prove a pre-exec
failure or a dead process. The host does not write `SESSION_START_FAILED` after
exec or stop the child solely because the start record could not be persisted.
Pre-exec failure attempts `SESSION_START_FAILED`; failure of that append is
reported together with the launch failure. Neither path guarantees a durable
outcome when storage fails.

AgentD documentation must preserve this uncertainty and must not introduce a
local shutdown or recovery policy to satisfy the superseded stronger promise.

## Open questions

### 1. How many simultaneous control clients must one session support?

The answer determines whether a fixed connection limit is sufficient or whether connection workers require
explicit ownership beyond the current detached-thread model.

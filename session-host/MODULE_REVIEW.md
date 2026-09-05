# Module Review: `session-host`

Date: 2026-09-07
Status: reviewed in isolation; contract questions remain open

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
- Remove `journal_available` if journal failure becomes session-fatal. It currently suppresses repeated logging
  but does not expose a recoverable or durable state.

## Proposed conceptual model

- One metadata manifest for discovery and one journal as the durable source of session history.
- One operation sequence high-water mark for host-lifetime replay protection, without a result ledger.
- One ordinary-effect serialization path, with explicit `TERMINATE` bypass for blocked PTY input.
- One maintenance reconciliation operation for compression and retention.
- Three failure classes: connection-local delivery failure, non-fatal metadata refresh failure, and fatal loss of
  the authoritative journal writer.

## Incremental migration path

1. Resolve the journal-failure and post-exec start-outcome questions below and update the protocol text first.
2. Add fault tests for the selected behavior before changing process cleanup or PTY-reader coordination.
3. Apply one explicit fatal-journal path if the journal remains authoritative; keep metadata refresh failures
   non-fatal.
4. Merge the maintenance commands without changing reconciliation or retention behavior.
5. Define and enforce a control-client bound only when the required concurrency is known.

## Do not change

- Keep metadata free of journal position and lifecycle replicas.
- Keep `operationSequence` as the only host replay guard; do not restore a result ledger without a new concrete
  requirement.
- Keep `TERMINATE` independent of the ordinary effect mutex.
- Keep grace periods, escalation, and termination retries outside the host.
- Preserve raw PTY bytes, journal event ordering, durable acknowledgement before retention, and Linux descendant
  identity checks.
- Preserve the documented macOS process-tracking limitations and current Windows unsupported status.

## Open questions

### 1. Should the host continue after losing journal output?

The current behavior conflicts with the stated purpose of `session-host`. The task requires a durable ordered
journal and preservation of terminal bytes for deterministic replay
([native session-host task](../docs/plans/current-work/native-session-host/TASK.md)). The availability requirement
only says that the host and child survive the process that launched them
([module README](README.md)); it does not require the child to continue after loss of the host's own journal.

`copy_pty_output` currently discards a PTY chunk when its append fails and may append later chunks after the
writer recovers (`src/platform/unix.rs`). `JournalWriter::append_at` advances the event ID only after a successful
append (`src/journal.rs`). A reader therefore sees an apparently continuous event sequence and cannot detect that
terminal bytes were lost between records.

The local `journal_available` flag only suppresses repeated stderr output. It is absent from `STATUS` and durable
state, so it does not make the degraded session observable or recoverable.

The preferred resolution is to treat loss of the authoritative journal writer as fatal to the session. The host
should stop accepting controls, terminate and reap its owned process tree, and continue draining the PTY only as
needed to avoid blocking cleanup. If process availability must instead win over deterministic replay, the
contract needs an explicit observable gap/degraded-state model before this behavior can be considered safe.

### 2. What happens when `PROCESS_STARTED` cannot be persisted after exec?

The child has crossed the exec boundary, so writing `SESSION_START_FAILED` would record a false lifecycle fact.
The current implementation instead logs the failed `PROCESS_STARTED` append and publishes a live session. That
conflicts with the protocol promise of exactly one durable start outcome and with journal-based recovery, which
then has no authoritative record that the process started.

The preferred resolution is to publish the live session only after `PROCESS_STARTED` is durable. If that append
fails, the host should perform immediate owned-process cleanup, return a distinct post-exec persistence failure,
and must not write `SESSION_START_FAILED`. The protocol should state that exactly one start outcome is guaranteed
when its durable append succeeds; storage failure can leave no durable outcome but must never create a live
session without `PROCESS_STARTED`.

This cleanup is failure containment for a host invariant, not server-owned graceful shutdown: it has no grace
period, escalation policy, or effect retry.

### 3. How many simultaneous control clients must one session support?

The answer determines whether a fixed connection limit is sufficient or whether connection workers require
explicit ownership beyond the current detached-thread model.

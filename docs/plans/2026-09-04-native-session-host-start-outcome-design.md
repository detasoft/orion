# Native Session-Host Start-Outcome Contract Design

## Context

The native host creates its first journal segment before it starts the child,
but launch failures currently return without a journal event. In particular,
`exec`, child working-directory, and child sandbox setup failures leave a
readable but empty journal. AgentD cannot distinguish that state from a host
that has not finished starting. The successful `PROCESS_STARTED` observation
is also appended with buffered durability.

The AgentD command-orchestration design requires every host-created journal to
produce one authoritative start outcome. A start that fails before a native
journal exists remains AgentD's responsibility and is represented by its
bounded in-memory synthetic journal.

## Contract

Add hosted-process event `0x0203 SESSION_START_FAILED`. Its version 1 CBOR
payload is:

```text
[commandId, diagnostic, omittedByteCount]
```

`commandId` is the validated Agent command identifier for the `START_SESSION`
attempt. `diagnostic` is strict UTF-8 and contains at most 1 MiB. A longer
diagnostic retains a UTF-8-safe prefix of at most 64 KiB and suffix of at most
960 KiB; `omittedByteCount` records the number of removed UTF-8 bytes. This
task does not redact diagnostic content. Secret redaction remains the separate
release prerequisite already tracked under AgentD.

The native CLI gains a required `--start-command-id` option. The AgentD
`SessionSpec` carries the corresponding `CommandId`, and `NativeRuntime` passes
it unchanged to the host. Validation uses the existing shared CommandId
character and length contract.

For each journal successfully created by a launch attempt, the native host
must write exactly one of these records before returning from the start phase:

- durable `PROCESS_STARTED` after the child has crossed the `exec` boundary;
- durable `SESSION_START_FAILED` when initialization fails before that
  boundary.

No direct control response, manifest field, PID observation, or host exit is a
substitute for this journal outcome.

## Startup Ownership and Data Flow

`run_session` keeps the `JournalWriter` in a pending-start state after journal
creation. A small start-outcome coordinator is the only code allowed to
resolve that state. Initialization after journal creation returns either the
confirmed child resources or a `HostError`:

```text
validate native options and sandbox (no journal)
  -> create endpoint and journal
  -> initialize metadata, acknowledgement state, and PTY child
     -> error: append_durable SESSION_START_FAILED, then return the error
     -> exec confirmed: append_durable PROCESS_STARTED, then enter live state
```

The child continues to report sandbox, `chdir`, environment, and `exec`
failures through the existing close-on-exec setup pipe. The parent translates
that failure into the standard `HostError` diagnostic and resolves the pending
outcome; the child never writes the journal.

Configuration work that can fail independently of journal state remains before
journal creation where practical. Errors before the journal boundary produce
no native record so AgentD can use its synthetic failure journal without a
competing source of truth.

Once success is durable, later runtime failures do not create a start-failure
record. If writing either outcome itself fails, the host returns an error that
retains both the launch failure and the outcome-write failure where applicable;
it must never silently claim that the contract was fulfilled.

## AgentD Handoff

The current AgentD runtime deletes a session directory whenever the tentative
host exits before handoff. That would erase a native `SESSION_START_FAILED`
record. Cleanup will therefore remove a failed launch directory only when no
readable native journal exists. Once a journal is readable, AgentD preserves
the directory for the journal reader and later reconciliation, even when the
host process has exited.

The runtime still reports its transient `SessionLaunchResult`; callers must
derive the authoritative start outcome from the journal. The later AgentD
command-orchestration task owns typed Java decoding and server replication of
the new record.

## Compatibility

The native protocol encoder and journal reader recognize both start outcomes.
Golden start-outcome fixtures are generated from the Rust encoder and checked
in byte-for-byte under both `session-host/protocol/fixtures` and
`agent-protocol/protocol/fixtures`. The Java transport decoder may initially
surface the new records as opaque while preserving their exact bytes. Adding
the Java typed payload is intentionally left to the existing AgentD protocol
prerequisite plan.

The protocol READMEs document the new allocation, payload bounds, durability,
and exactly-one rule. Existing version 1 event IDs and fixtures do not change.

## Verification

Tests cover:

- required CLI parsing and rejection of malformed start command IDs;
- exact encoding, bounds, and golden bytes for both start outcomes;
- a normal child producing one durable `PROCESS_STARTED` and no failure;
- missing executable and missing working directory producing one
  `SESSION_START_FAILED`, no `PROCESS_STARTED`, and the original CommandId;
- another initialization failure after journal creation producing the same
  outcome shape;
- AgentD passing the CommandId to the CLI and preserving a readable failed
  journal instead of deleting it;
- focused Rust and AgentD tests followed by the repository verification and
  post-commit `make test` required by the task workflow.

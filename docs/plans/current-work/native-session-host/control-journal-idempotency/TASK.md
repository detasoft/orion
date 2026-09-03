# Make Control-to-Journal Delivery Idempotent

Status: todo
Depends on: stable control and journal framing

Give retryable control operations an AgentD-assigned monotonic
`operationSequence` while preserving the complete client-originated command
envelope byte-for-byte in the journal.

## Scope

- Apply `operationSequence` to `INPUT`, `SIGNAL`, and `APPEND_EVENT`; retries
  reuse it, new operations increase it, and gaps are valid.
- Assign every accepted operation the host's monotonic journal timestamp and
  store both sequences with the unchanged client command envelope.
- Keep client identifiers, including `requestId` and future fields, opaque to
  AgentD and session-host delivery logic.
- Prevent repeated journal appends and external side effects with an accepted
  operation high-water mark and a bounded unacknowledged-operation table.
- Add a non-journaled `ACK_JOURNAL` with a monotonic journal-timestamp
  watermark. Apply repeated watermarks idempotently to retention and checkpoint
  state, reply `ACCEPTED` only after that application is durable, and discard
  acknowledged table entries without lowering the accepted-operation
  high-water mark.
- Keep the host watermark local to retention; it is not AgentD replication
  authority and is not session metadata state.
- Reject stale operations and reuse of one `operationSequence` for different
  command bytes; never silently execute them as new operations.
- Cover gaps, retries before acknowledgement, durable acknowledgement cleanup,
  repeated and stale watermarks, and unchanged client envelopes for every
  retryable command type.

## Boundary

This task changes the `session-host` protocol and implementation only. AgentD
generation, retry timing, and journal-tail integration remain in the AgentD
task tree and consume the resulting compatibility fixtures.

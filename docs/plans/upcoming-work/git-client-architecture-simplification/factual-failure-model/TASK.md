# Make Git Client Failures Factual

Status: todo
Depends on: ../phase-aware-transport-exchange/TASK.md

Keep structured Git client diagnostics while removing retry and phase claims
that the layer producing a failure cannot know reliably.

## Scope

- Retain structured failure kind, operation stage, message, and cause for logs
  and higher-level policy.
- Remove the low-level `retryable` decision from client and transport failures;
  retry policy belongs to the external synchronization operation that knows
  direction, idempotency, ref state, and attempt history.
- Report a whole-operation timeout or cancellation as an operation-level stage
  instead of always reporting `OPEN`.
- Classify advertisement, negotiation, pack transfer, and report-status failures
  consistently across Smart HTTP and SSH.
- Let caller-owned pack source, pack target, progress, and planning failures
  escape as ordinary caller exceptions with their original cause; do not add a
  client failure kind for local storage or callback code.
- Ensure remote transport I/O is translated at the transport boundary so it
  remains distinguishable from those caller exceptions.
- Keep valid receive-pack rejection as a domain result with unpack and per-ref
  status rather than turning it into an exceptional transport failure.
- Replace the current failure contract directly; do not retain a deprecated
  retry flag or compatibility result type.

## Completion Criteria

- Equivalent malformed advertisement and protocol failures have the same stage
  on Smart HTTP and SSH.
- Tests cover operation timeout during open, negotiation, pack transfer, and
  report status without falsely assigning every case to `OPEN`.
- Local pack source and target failures escape as ordinary exceptions, while
  remote read/write failures remain structured client results; both retain
  their original cause.
- Existing authentication, authorization, host-key verification, server error,
  size-limit, cancellation, and remote-ref rejection diagnostics remain covered.

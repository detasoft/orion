# Align AgentD with the Stabilized Session-Host Contract

Status: todo (runtime alignment remains; documentation audit completed 2026-09-07)
Parent: ../TASK.md
Current contract: ../../../2026-09-03-native-control-journal-idempotency-design.md
Session-host review: ../../../../../session-host/MODULE_REVIEW.md

Bring every remaining AgentD session-host integration path to the final native
control, journal, metadata, and lifecycle contracts after the focused control
model changes are complete. The documentation audit accepts the current native
implementation as the baseline; it did not change Java or Rust runtime behavior.
The linked contract records the current Java/native interface differences.

## Scope

- Audit AgentD launch, discovery, control transport, journal projection,
  recovery, retention acknowledgement, and status handling against the current
  session-host protocol and compatibility fixtures.
- Treat `RECEIVED` as transient admission only. Observe operation completion
  through `COMMAND_RESULT` and preserve the documented unknown/partial-effect
  semantics after ambiguous delivery or a missing result record.
- Use the single schema-2 operation sequence and exact opaque command envelope
  without restoring a duplicate native command identity, result ledger, or
  schema-1 operation fallback.
- Put the operation sequence in the frame header; encode only envelope length,
  envelope, and effect in the payload. Replace the Java timestamp/duplicate ACK
  model with empty or rejected `RECEIVED` and correlate results by sequence.
- Preserve uncertain delivery across reconnects: a stale rejection cannot
  resolve an earlier attempt. A journal suffix does not expose admissions with
  pending or missing results, so recorded maxima alone do not prove a fresh
  sequence. Resolve allocation with command orchestration before completion.
- Send `ACK_JOURNAL` through the same schema-2 operation contract and advance
  it only from a server-durable journal prefix.
- Consume the four-byte `TERMINATE` effect and effective sandbox status without
  adding AgentD-owned process-tree or host-shutdown policy.
- Preserve current start-outcome and journal-failure behavior: output append
  failures can discard chunks while the child continues; failed
  `PROCESS_STARTED` persistence can leave a live host without a start record.
  Neither condition implies a native shutdown or a recoverable journal gap.
- Remove superseded Java branches, adapters, aliases, retry assumptions, and
  legacy-only tests once every real consumer uses the canonical path. Remove
  legacy-only negative coverage in a separate commit as required by AGENTS.md.

## Acceptance

- AgentD can launch and rediscover a real session host, issue every established
  operation, reconnect after an uncertain control exchange, and distinguish
  admission from the durable result observed in the journal.
- Journal resume and `ACK_JOURNAL` use one server-confirmed durable prefix and
  preserve retention-gap reporting.
- Start success, pre-exec start failure, missing operation result, partial
  effect, graceful terminate, force terminate, and sandbox status match the
  current session-host contract, including continued service after journal
  append failure and no fabricated start outcome after exec.
- Shared protocol fixtures and real-host integration tests cover unsigned
  sequences above `i64::MAX`, unknown envelope fields, reconnect, and ACK.
- No obsolete schema-1 operation path, duplicate command identity, private
  durable command cursor, or AgentD-owned host termination coordinator remains.

## Boundary

This task owns AgentD conformance with the established native host contracts
and removal of superseded AgentD paths. It does not change the session-host wire
or journal formats, implement server command orchestration, or add local terminal
UI behavior.

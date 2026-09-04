# Make Journal Durability Operations Explicit

Status: todo
Depends on: [reduced journal writer API](../journal-writer-api/TASK.md)

Replace the unused global durability policy with operations that express the
actual semantic durability boundary of each journal record.

## Scope

- Provide explicit buffered append, durable append, and durable finish
  operations instead of selecting durability through a writer-wide mode.
- Keep high-volume PTY records buffered. Durably append session-start outcomes
  and accepted command records at their existing authority boundaries.
- Make the authoritative `PROCESS_EXITED` record part of `finish_durably`, and
  call `sync_data` before successful host completion.
- Remove `Durability::EveryRecord` and the production `Buffered` configuration
  choice once callers select the operation they require directly.
- Define the final barrier's machine-crash guarantee, including a final append
  that crosses a segment boundary, without changing encoded records or the wire
  protocol.

## Acceptance

- Every production append site selects buffered, durable, or final semantics by
  operation rather than by global configuration.
- A successful start outcome, command acceptance, and host finish return only
  after their required durability barrier; ordinary PTY traffic does not pay a
  per-record sync cost.
- The sole authoritative exit fact is durably stored before normal host exit,
  and failures to append or sync it are surfaced rather than reported as a
  successful finish.
- Tests cover each durability operation, final process exit, sync failure, and
  final rotation while preserving byte-identical CBOR fixtures.

## Boundary

This task owns the journal API and durability guarantees. Termination trigger,
escalation, reaping, and shutdown sequencing remain in
`../termination-coordination/TASK.md`.

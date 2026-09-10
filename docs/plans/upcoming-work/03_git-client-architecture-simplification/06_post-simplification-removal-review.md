# Re-audit Removable Git Client Machinery

Status: todo
Depends on: 01_single-session-request-planning.md,
02_phase-aware-transport-exchange.md,
03_factual-failure-model.md,
04_session-inactivity-timeouts.md,
05_smart-http-ssh-behavior.md,
../02_git-wire-architecture-simplification/03_parser-storage-boundary.md

Repeat the read-only Git client simplification review after the four preceding
changes and the parser/storage boundary are complete. Record already removed
items and create focused implementation tasks for every remaining deletion;
do not mix speculative code removal into the audit.

## Candidates to Verify

- `BoundedBodySubscriber`, `SwitchingInput`, `FinishingOutput`, and complete
  Smart HTTP advertisement materialization.
- The intermediate `List<String>` used before advertisement parsing in
  `GitBlockingClientWire`.
- `GitSshClientTransport implements AutoCloseable` and its empty `close()` when
  injected clients remain caller-owned and strict clients remain session-owned.
- Unused `REMOTE_REF_REJECTED` and `REMOTE_UNPACK_FAILED` failure kinds if
  receive-pack rejection remains a domain result.
- Advertisement fields in `GitUploadPackResult` and `GitReceivePackResult` once
  request planning already observes the operation's advertisement.
- The duplicate `GitClientService` after parser API neutralization provides the
  equivalent shared service identity.

## Completion Criteria

- The review checks source, tests, repository consumers, history, and the Maven
  module graph rather than relying on the original audit alone.
- A report under `docs/reviews/` marks every candidate `removed`, `retain`, or
  `confirmed for deletion` with concrete evidence.
- Every confirmed remaining deletion has a separate task node with its required
  characterization tests, including Smart HTTP and SSH behavior where relevant.

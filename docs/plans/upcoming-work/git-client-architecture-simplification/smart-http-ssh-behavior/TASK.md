# Verify Smart HTTP and SSH Behavioral Parity

Status: todo
Depends on: ../single-session-request-planning/TASK.md,
../phase-aware-transport-exchange/TASK.md,
../factual-failure-model/TASK.md,
../session-inactivity-timeouts/TASK.md

After the client architecture changes, research the observable contract of
Smart HTTP and SSH and add shared characterization for behavior that must not
depend on their different physical lifecycles.

## Scope

- Inventory existing transport tests and identify behavior asserted for only
  Smart HTTP or only SSH without a protocol-specific reason.
- Prefer one shared contract test fixture or clearly paired scenarios over two
  independently evolving copies when that remains readable.
- Cover one-session request planning, advertisement identity, request
  completion, intermediate flush, streaming backpressure, pack limits, progress,
  remote rejection, failure stages, inactivity, cancellation, and close races.
- Assert physical lifecycle differences explicitly: Smart HTTP uses one
  discovery `GET` plus one command `POST`, while SSH uses one authenticated
  channel and one service invocation.
- Include slow-progress and fully stalled reads and writes so timeout semantics
  are compared rather than inferred from transport-specific unit tests.
- Preserve transport-specific authentication, content-type, redirect,
  known-hosts, and channel-command tests outside the shared contract.
- Do not add compatibility APIs or production abstractions solely to share test
  code. Record any newly exposed production discrepancy as a separate task
  instead of silently weakening the common assertions.

## Completion Criteria

- Every common behavior listed above is exercised against both Smart HTTP and
  SSH, with intentional differences named in the test or fixture.
- The comparison includes successful fetch and push plus at least one failure,
  timeout, cancellation, and resource-cleanup scenario in each direction.
- A short report under `docs/reviews/` records the parity matrix, justified
  differences, missing coverage added, and follow-up tasks for real behavioral
  divergence.

# Unify the AgentD Native Control Contract

Status: in progress
Design: ../../../2026-09-03-native-control-journal-idempotency-design.md
Parent: ../TASK.md
Blocks: ../command-orchestration/TASK.md

- [ ] Replace the split Java native-control model with one schema 2 operation
  contract for `INPUT`, `RESIZE`, `SIGNAL`, and `TERMINATE`.
  - Owner: codex, session native-control-schema2-8f31, started 2026-09-05 Europe/Amsterdam.

## Scope

- Carry the unsigned `operationSequence`, bounded command identity, exact opaque
  command envelope, and typed effect in every schema 2 operation request.
- Keep `STATUS` and existing response decoding on their current schema 1 wire
  contract.
- Remove INPUT-only retry and deduplication behavior from the Java control
  client; all four operation controls use the same delivery policy.
- Remove every obsolete schema 1 artifact from the operation-control path:
  legacy constructors, branches, retry assumptions, fixture helpers, tests,
  comments, and compatibility aliases. Retain schema 1 only for explicitly
  preserved `STATUS` and `ACK_JOURNAL` requests and response frames.
- Cover the shared schema 2 fixture, including unknown envelope fields and
  sequences above `i64::MAX`.
- Exercise the encoder against a real `session-host` instance before closing
  the task.

## Boundary

This task owns the Java API, native control encoder/client, and their contract
tests. It does not implement server command orchestration, host-side durable
operation journaling, Rust schema 1 compatibility, or command scheduling.

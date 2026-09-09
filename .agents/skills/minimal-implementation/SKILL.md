---
name: minimal-implementation
description: >-
  Use when implementing features, fixing bugs, refactoring code, or reviewing
  changes or subsystem architecture, especially when work touches ownership,
  state, contracts, module boundaries, or potentially duplicated concepts.
---

# Minimal Implementation

Implement the requested behavior with the minimum necessary architecture.
Preserve required behavior and invariants, reuse existing mechanisms, and
remove concepts that have no current justification. Fewer changed lines do
not compensate for extra state, parallel paths, or unclear ownership.

## Scope and operating mode

1. Determine the mode from the user's request before taking action.
   Implementation requests include writing code, tests, and verification.
   Review or audit requests are read-only: inspect and report, without editing
   source, tests, configuration, generated files, or repository metadata.
   Implement a review recommendation only when the user requests that change.
2. Apply the same conceptual analysis before implementation and during the
   final self-review. Self-review is part of authorized implementation; fix
   task-local problems it reveals.
3. Bound the analysis to the requested change and its real consumers. For a
   local edit, keep the analysis local. For a subsystem or repository audit,
   inspect the broader flows and boundaries before making broad claims.
4. Follow explicit user choices and repository instructions. This skill does
   not authorize unrelated cleanup, contract changes, commits, or integration.
5. For Java, Kotlin, Spring, or other JVM code, also read
   [references/java-kotlin.md](references/java-kotlin.md).

## 1. Define the required behavioral delta

Before choosing an implementation, state:

- **Required result:** the observable statements that must become true.
- **Preserved behavior:** existing behavior the task does not ask to change.
- **Invariants:** ownership, lifecycle, data integrity, compatibility, and other
  constraints that must remain true, with evidence from code, tests, contracts,
  or repository rules.
- **Scope:** the operation or subsystem being changed and explicit non-goals.

Describe the requirement in behavioral terms. Do not silently turn "support
this operation" into "introduce a service, framework, or protocol." If the user
requires a particular mechanism, treat that as a constraint.

Distinguish verified requirements from assumptions. Resolve a material unknown
before relying on it to change behavior or a contract; inspect available
evidence first and ask the user when the missing decision cannot be inferred.

## 2. Establish and verify the current model

Build the relevant model before proposing simplifications. Identify:

- modules, responsibilities, entry points, and runtime composition;
- callers, implementations, control flow, data flow, and error propagation;
- owners of mutable state, durable state, resources, and processes;
- lifecycle, shutdown, restart, and recovery boundaries;
- public, internal, wire, and persisted contracts;
- concurrency, ordering, cancellation, and backpressure assumptions;
- different representations of the same identity, status, or domain concept.

Trace the major end-to-end flows affected by the request. Search for callers,
implementations, wiring, conversions, lifecycle hooks, adjacent modules,
persisted representations, and meaningful tests. Do not infer the architecture
from names, documentation, or a few prominent classes. Treat them as hypotheses
and verify them against actual behavior.

Record contradictions and uncertainty. State inspection limits when reporting;
do not imply exhaustive coverage of areas that were not inspected.

Identify the closest existing mechanism for the required behavior: a component,
protocol/message, state machine, lifecycle hook, storage or configuration
mechanism, event path, or error model. Do this before creating a new mechanism.

## 3. Challenge concepts and contracts explicitly

Check the relevant code for:

- one domain concept represented differently in different modules;
- similar operations following different production paths without a requirement;
- interfaces that callers bypass or that provide no meaningful substitutability;
- single-consumer public types, forwarding wrappers, and speculative extension points;
- factories, providers, registries, managers, or services that only add indirection;
- adapter or DTO chains that preserve no useful boundary;
- repeated validation, policy, transitions, conversion, orchestration, or recovery;
- parallel hierarchies, duplicate lifecycle/error models, and multiple sources of truth;
- state propagation or synchronization of facts already owned elsewhere;
- layers whose main purpose is to compensate for another layer;
- configuration with only one meaningful value;
- strong guarantees with no verified consumer.

Prioritize semantic duplication over textual duplication. Do not automatically
extract a helper, base class, strategy, or factory when similar code is found.
First consider deleting, merging, inlining, narrowing visibility, deriving
state, or assigning the behavior to one existing owner.

For each non-trivial or suspicious concept, answer:

1. Which current requirement makes it necessary?
2. Can an existing mechanism express that behavior?
3. Does it remove complexity, or merely relocate it?
4. Which real callers need its strongest guarantee?
5. Does it protect correctness, ownership, isolation, lifecycle, persistence,
   a framework constraint, or an explicit compatibility promise?
6. If it has one consumer, can it be private, internal, nested, colocated,
   inlined, or removed entirely?
7. Would it still be introduced given the requirements and evidence known now?

A single implementation or consumer is a reason to investigate, not proof of a
defect. Preserve a separate type or boundary when verified behavior needs it.
Do not retain it solely for a hypothetical future consumer or to make mocking
convenient.

### State machines and coordination

List observable states, internal states, durable facts, and transitions for
each relevant state machine. Identify the authoritative owner of each fact.

Check whether a single source of truth, derived state, a pull/query model,
idempotent operations, or reconstruction on restart can replace coordination.
Inspect recovery paths that reconstruct an in-memory protocol between
components. Do not introduce new durable state just to remove local state.

### Contract weakening

Before removing or weakening a guarantee, record all of:

- the exact guarantee: atomicity, durability, ordering, uniqueness, immediacy,
  availability, API compatibility, or another explicitly named property;
- actual consumers and evidence of their reliance;
- whether it protects required correctness, convenience, an implementation
  detail, or only a historical design;
- newly possible failures and how they are contained;
- the concrete structure or coordination that disappears;
- wire, persistence, source/binary compatibility, and migration implications.

Absence of an immediately visible caller is not proof that weakening is safe.
Preserve complexity that protects a verified invariant. A required behavior
cannot be weakened merely because the implementation would become shorter.

## 4. Choose and explain the smallest valid implementation

Compare valid approaches in this order:

1. Preserve behavior not explicitly changed by the task.
2. Preserve architectural invariants and required boundaries.
3. Minimize public contract changes.
4. Minimize affected modules.
5. Minimize changed files.
6. Minimize new types, interfaces, and classes.
7. Minimize configuration and persistent state.
8. Minimize dependencies.
9. Minimize code added.

Prefer, in order: no structural change; local logic in an existing concept; a
small extension of that concept; a narrow internal helper; a broader abstraction
only when the preceding choices cannot satisfy the requirement.

For every proposed new abstraction, type, service, module, protocol field,
configuration option, persistent state, dependency, or public contract change,
state the current requirement that needs it and why an existing mechanism
cannot provide the same behavior. Remove proposals with no concrete reason.
Future extensibility is not a requirement unless the user explicitly asks for it.

Before coding a non-trivial change, present a brief delta statement:

```text
Current model:
Required behavioral delta:
Behavior and invariants preserved:
Chosen implementation and why a smaller option is insufficient:
New concepts: none, or each concept with its justification
```

For a small local change, the same reasoning can fit in a few sentences.
Do not invent alternative architectures merely to populate this statement.

## 5. Split large work into complete, verifiable parts

Before implementing a task with multiple behavioral outcomes or substantial
cross-module work, divide it into ordered behavioral slices. Each slice must
specify:

- one concrete result or coherent behavioral change;
- affected components and the intended change in each;
- dependencies on preceding slices;
- behavior and invariants preserved at that checkpoint;
- tests or other checks that demonstrate completion.

Prefer the smallest useful end-to-end change. Avoid plans that build all
interfaces, all storage, and all services before any behavior can be verified.
A necessary enabling change is acceptable when its dependency and verification
are explicit. Do not fragment a simple local edit into artificial subtasks.

Implement, verify, and review a slice before expanding to the next. Each
completed slice must leave a coherent build and working production path. Keep
incomplete work explicit; one completed slice does not mean the entire task is
complete. Follow repository task-tree and commit rules when recording work.

When replacing an internal API, behavior, or concept:

- update every real in-repository consumer and delete the old production path
  in the same replacement slice;
- do not retain deprecated aliases, adapters, compatibility shims, dual
  read/write paths, migration modes, fallback paths, or feature flags for the
  replaced model;
- preserve required wire and persisted contracts through the single canonical
  implementation;
- preserve meaningful behavior coverage through the replacement API and follow
  repository rules for separately removing legacy-only negative tests.

Tests and hypothetical future or external consumers do not justify preserving
an old internal API. If a replacement crosses modules, keep the consumer
updates and removal together; split other independent behavior around that
boundary. Do not create temporary dual architectures just to make slices small.

## 6. Implement and verify within scope

Keep each edit tied to the required result, a preserved invariant, or necessary
verification. Avoid unrelated renames, formatting, moves, and cleanup. Preserve
established patterns unless they prevent the required behavior.

For functional changes, add or extend tests in the same change. Cover the
straightforward happy path and at least one meaningful scenario such as update,
invalid state, reload, multiple backends, cancellation, or recovery. Select
scenarios from the actual risks. Verify required behavior rather than mirroring
implementation details or asserting only that an old concept is absent.

Follow repository rules for test order, commands, and verification ownership.
Run checks appropriate to the changed behavior and affected boundaries.
Inspect outputs before claiming success. State failures, checks not run, and
remaining limitations explicitly. Unrelated discoveries do not expand the
implementation scope.

## 7. Review the actual result

Compare the complete diff with the required behavioral delta, not just the
implementation plan. Repeat the concept checks within affected modules and
across their interactions.

Explicitly check whether the result can use fewer abstractions, public APIs,
states, configuration options, dependencies, modules, files, or types. Check
whether existing concepts can absorb the behavior and whether any addition
serves only future flexibility.

Remove task-local excess that is unnecessary for the requested behavior, an
invariant, or verification. Verify again after relevant fixes. Report unrelated
simplification opportunities separately. Name the evidence that justifies
retaining apparently complex parts.

For a dedicated review or audit, produce evidence-backed findings only. Rank
them by the amount of structure, duplicated state, edge cases, or coupling they
can remove, rather than by ease of implementation. Each finding must include:

- **Finding:** the structural problem.
- **Evidence:** concrete files, symbols, flows, and callers.
- **Requirement/design pressure:** what appears to justify the current design;
  distinguish evidence from speculation.
- **Smallest simplification:** the narrowest viable alternative.
- **Contract and consequences:** guarantees changed, risks, failure containment,
  compatibility effects, and capability or flexibility lost.
- **Confidence:** high, medium, or low, with the main uncertainty.

For a broad audit, also report scope and coverage, the verified current model,
candidate concepts to delete and their removal conditions, a proposed simpler
model, an incremental path with validation points, parts that must remain, and
open questions. Derive a simpler alternative for each reviewed subsystem or
explain which verified invariants prevent further reduction.

If no meaningful architectural issue is supported, say so. Do not fill the
report with style advice, speculative defects, or rearrangements that leave the
same conceptual complexity.

## 8. Required final summary

After implementation, always give a self-contained summary in the user's
language. Include these four parts explicitly; scale their detail to the work
without omitting any part:

1. **What was being solved:** the original problem and the required outcome.
2. **How it was solved:** the chosen approach and the key implementation
   decisions. Mention new or removed concepts when relevant.
3. **Which parts changed, and how:** name each materially affected module,
   component, or file group, link relevant files, and describe its specific
   behavioral or responsibility change. For a replacement, make the old and new
   behavior clear. A list of filenames or "updated the code" is insufficient.
4. **Verification and remaining work:** checks actually run and their outcomes;
   state checks not run, known failures, limitations, and incomplete work when
   applicable. Do not report the whole task complete if required slices remain.

Explain the resulting change, not the chronology of tools used. Group related
files when they implement one change; use a small table when it clarifies
several affected parts. The user must be able to understand the result without
reading earlier progress messages or reconstructing it from the diff.

For review-only work, summarize the problem, inspected scope, findings, and
proposed changes. State that no implementation changes were made, and do not
describe proposals as completed work.

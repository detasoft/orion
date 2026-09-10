---
name: orion-minimal-implementation
description: >-
  Use when implementing or reviewing Orion changes that affect behavior,
  ownership, state, contracts, lifecycle, or module boundaries.
---

# Orion Minimal Implementation

Make the requested behavior true with the smallest architectural delta. Preserve
verified invariants, reuse existing mechanisms, and remove concepts without a
current requirement.

## Scope and mode

Determine the mode from the request:

- implementation may edit only the requested result and its necessary tests or
  documentation;
- review, explanation, and audit are read-only unless the user requests changes;
- `orion-review` is the narrow audit exception: it maintains and commits
  `MODULE_REVIEW.md`, but does not repair production code without authorization.

Bound analysis to the requested change and real consumers. A repository or
subsystem audit needs broader evidence than a local edit. Follow explicit user
choices, `AGENTS.md`, and the selected repository workflow.

For Java, Kotlin, Spring, or other JVM implementation, also read
[references/java-kotlin.md](references/java-kotlin.md).

## Define the required delta

Before choosing an implementation, establish:

- **Required result:** observable statements that must become true.
- **Preserved behavior:** existing behavior the request does not change.
- **Invariants:** ownership, lifecycle, integrity, compatibility, and other
  verified constraints.
- **Scope and non-goals:** affected operations or subsystems and what remains
  outside the change.

Distinguish requirements from assumptions. Resolve a material unknown before it
changes behavior or a contract. Do not turn a required behavior into a request
for a new service, framework, protocol, or state unless that mechanism is itself
required.

## Establish the current model

Trace the relevant production path before simplifying it:

- modules, responsibilities, entry points, composition, and callers;
- control/data flow and error propagation;
- ownership of mutable and durable state, resources, and processes;
- lifecycle, shutdown, restart, and recovery boundaries;
- public, internal, wire, and persisted contracts;
- concurrency, ordering, cancellation, and backpressure;
- different representations of the same identity, status, or concept.

Search implementations, wiring, conversions, hooks, adjacent modules, persisted
representations, and meaningful tests. Names and documentation are hypotheses
until verified against behavior. Record contradictions, uncertainties, and
inspection limits.

Identify the closest existing mechanism for the required result before proposing
a new one.

## Challenge concepts

Investigate:

- one concept represented differently in separate modules;
- several production paths for the same operation;
- interfaces callers bypass or that provide no useful substitutability;
- single-consumer public types, forwarding wrappers, and speculative extension
  points;
- factories, providers, registries, managers, or services that only add
  indirection;
- adapter or DTO chains without a real boundary;
- repeated policy, state, validation, transitions, conversion, recovery, or
  orchestration;
- duplicate lifecycle/error models and multiple sources of truth;
- synchronized facts that can be derived from their owner;
- configuration with one meaningful value;
- strong guarantees without a verified consumer.

For every suspicious concept, ask:

1. Which current requirement makes it necessary?
2. Can an existing owner or mechanism express the behavior?
3. Does it remove complexity or relocate it?
4. Which real consumer needs its strongest guarantee?
5. Does it protect correctness, ownership, isolation, lifecycle, persistence, a
   framework constraint, or explicit compatibility?
6. Can a single-consumer concept be private, internal, nested, colocated,
   inlined, or removed?
7. Would it still be introduced from current requirements and evidence?

A single implementation or consumer is evidence to investigate, not proof of a
defect. Preserve a boundary with a verified purpose. Do not preserve one for a
hypothetical future consumer or test-mocking convenience.

Prefer deletion, merging, inlining, narrower visibility, derived state, or one
existing owner before a helper, base class, strategy, factory, or new service.

### State and coordination

List observable, internal, and durable states, transitions, and the authoritative
owner of each fact. Check whether derived state, a query model, idempotence, or
restart reconstruction can replace synchronization or coordination. Do not add
durable state merely to remove local state.

### Contract weakening

Before removing or weakening a guarantee, record:

- the exact guarantee;
- every actual consumer and evidence of reliance;
- whether it protects correctness or only convenience/history;
- newly possible failures and their containment;
- the concrete structure that disappears;
- wire, persistence, source/binary compatibility, and migration effects.

Absence of a visible caller is not proof that weakening is safe.

## Choose the smallest valid change

Compare valid implementations in this order:

1. preserve behavior not requested to change;
2. preserve architectural invariants and required boundaries;
3. minimize public contract changes;
4. minimize affected modules;
5. minimize changed files;
6. minimize types and abstractions;
7. minimize configuration and persistent state;
8. minimize dependencies;
9. minimize code.

Prefer no structural change, then local logic in an existing concept, a small
extension of that concept, a narrow internal helper, and only then a broader
abstraction. Justify every new public contract, type, service, module, protocol
field, configuration option, persistent fact, or dependency with a current
requirement that existing mechanisms cannot satisfy.

For non-trivial work, state:

```text
Current model:
Required behavioral delta:
Behavior and invariants preserved:
Chosen implementation and why a smaller option is insufficient:
New concepts: none, or each concept with its justification
```

When the selected workflow permits several checkpoints, split substantial work
into complete behavioral checkpoints. Each checkpoint must leave one working
production path and state its result, affected components, preserved invariants,
dependencies, and verification. Under Quick, keep the coherent result as its
single checkpoint.

When replacing an internal API, behavior, or concept, update every real
in-repository consumer and remove the old production path in the same
checkpoint. Do not retain aliases, compatibility shims, dual read/write paths,
migration modes, fallback paths, or feature flags for the replaced internal
model. Preserve required wire and persisted contracts through the canonical
path. Tests and hypothetical consumers do not justify a second path.

## Verify and review

Follow the selected workflow and the
[pre-commit verification table](../../../docs/definitions.md#pre-commit-verification).
Add or update meaningful tests whenever behavior changes. Verify observable
behavior and contracts, not source strings or private implementation details.

Do not run a new test while failure is mechanically guaranteed because its
required production source, type, or symbol does not exist yet. Record that the
test is not runnable for that exact reason, add the smallest production
definition needed to compile it, and make the first test run one that can
provide meaningful behavioral evidence. This avoids spending a test run merely
confirming an expected compilation failure; it does not waive later test or
pre-commit verification requirements.

Review the complete diff against the required delta. Explicitly ask whether it
can use fewer abstractions, APIs, states, options, dependencies, modules, files,
or types. Remove task-local excess and verify again. Report unrelated
simplification opportunities separately.

For a dedicated audit, rank findings by removable structure, duplicated state,
edge cases, and coupling. Every finding must include:

- **Finding:** the structural problem.
- **Evidence:** files, symbols, flows, and consumers.
- **Requirement/design pressure:** verified justification versus speculation.
- **Smallest simplification:** the narrowest viable alternative.
- **Contract and consequences:** guarantees, risks, containment, compatibility,
  and lost capability.
- **Confidence:** high, medium, or low, with the main uncertainty.

A broad audit also reports scope, current model, removable concepts and their
conditions, a simpler model, incremental validation, required complexity, and
open questions. If evidence supports no meaningful issue, say so.

## Final summary

In the user's language, report:

1. what was being solved;
2. how it was solved, including added or removed concepts;
3. which parts changed and how;
4. actual verification, limitations, remaining work, and unrelated state.

For review-only work, state that no implementation changes were made.

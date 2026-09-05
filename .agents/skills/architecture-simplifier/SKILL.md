---
name: architecture-simplifier
description: Perform a read-only review of a mature, non-trivial codebase or subsystem for architectural inconsistency, semantic duplication, unnecessary coordination, overly strong contracts, and opportunities to remove concepts safely. Use for architecture or complexity audits and simplification reviews; do not substitute it for ordinary PR, style, security, or correctness review.
---

# Architecture Simplifier

Review the code as a software architect trying to minimize the amount of architecture needed for the actual product requirements. Prefer deleting or merging concepts over adding generalized abstractions.

This is a diagnostic, read-only skill. Inspect and analyze the repository, but never modify source code, tests, configuration, build files, generated files, or repository metadata. Produce evidence-backed findings and simplification proposals only. Implementing any recommendation is a separate task outside this skill.

Treat current architecture, documentation, names, and boundaries as hypotheses rather than proof of intended design.

For Java, Kotlin, Spring, or JVM backends, also read [references/java-kotlin.md](references/java-kotlin.md).

## Establish Scope and Evidence

Start by determining the requested scope. For a repository-wide review, inspect the repository structure, build/module definitions, architecture documentation, public entry points, persistence boundaries, and representative tests before forming findings. For a focused review, trace the selected subsystem far enough into callers, implementations, persisted data, and adjacent modules to understand its real contract.

Do not infer the architecture from two or three prominent files. Search across the scope for implementations, callers, parallel concepts, lifecycle hooks, conversions, and tests. If the codebase is too large to inspect comprehensively, state what was inspected and label conclusions accordingly; never imply exhaustive coverage.

## Pass 1: Build and Verify the Model

Before proposing changes, form a compact architectural model covering:

- modules and responsibilities;
- control and data flow;
- ownership of mutable and durable state;
- lifecycle and recovery boundaries;
- public, internal, and persistence contracts;
- concurrency and ordering assumptions;
- representations of identity, status, and the same domain concept.

Trace at least the major end-to-end flows relevant to the scope. Verify the model against code and tests. Record uncertainty and contradictions instead of silently choosing the most convenient interpretation.

Do not suggest simplifications during this pass unless the user asked only for a quick hypothesis. Keeping modeling separate prevents early local observations from hardening into an incorrect global story.

## Pass 2: Challenge the Model

Search specifically for:

- one concept modeled differently in separate modules;
- similar operations taking different paths without a requirement that explains it;
- abstractions that callers frequently bypass;
- interfaces with no meaningful substitutability or only speculative implementations;
- adapters or DTO chains that preserve no useful boundary;
- repeated policy, validation, transition, orchestration, conversion, or recovery logic;
- parallel hierarchies and multiple sources of truth;
- layers created mainly to compensate for another layer;
- runtime coordination state that can be derived from durable facts;
- guarantees that are expensive to maintain but have no verified consumer.

Treat semantic duplication as more important than textual duplication. Do not mechanically respond with helpers, base classes, strategies, or factories. First decide whether the better remedy is to merge concepts, move ownership, weaken a contract, derive state, or delete the duplicated mechanism.

Actively derive a simpler alternative for every reviewed subsystem. A review that only describes the existing abstractions or rearranges them is incomplete. In the recommendations, first consider removing, merging, inlining, narrowing, or making a concept private before proposing another abstraction. Do not apply these changes.

When a class or component has only one consumer and no verified independent contract, prefer treating it as a non-public implementation detail colocated with its owner. Then ask whether it needs to remain a separate class at all. Preserve separate public types only when a real API, lifecycle, framework, compatibility, testing, or ownership boundary justifies them.

For every non-trivial abstraction ask:

1. What requirement makes this necessary?
2. Does it remove complexity or only relocate it?
3. Which real callers need its strongest guarantee?
4. Could a simpler invariant, pull/query model, idempotent operation, or reconstruction-on-restart replace coordination?
5. If built today with the knowledge present in the repository, would this abstraction still be introduced?
6. If it has only one consumer, can it become private or internal, be nested or colocated, be inlined, or disappear entirely?

## Evaluate Contract Weakening

It is valid to recommend a weaker contract when that removes substantial complexity without harming required quality. Do not call a trade-off safe merely because no caller was immediately visible.

For each proposed weakening identify:

- the exact guarantee removed;
- verified consumers and evidence of reliance;
- whether the guarantee protects correctness, convenience, an implementation detail, or history;
- newly possible failure modes and their containment;
- the concrete simplification obtained;
- compatibility and migration implications.

Distinguish atomicity, durability, ordering, uniqueness, immediacy, availability, and API compatibility rather than referring vaguely to “weaker consistency.” Preserve complexity that protects a real invariant.

## Inspect State Machines and Coordination

For explicit or implicit state machines, list observable states, internal states, durable facts, and transitions. Look for states that exist only because components synchronize replicas of knowledge. Test whether the system can instead use one durable source of truth, derived state, eventual observation, and idempotent retries.

Prefer local reasoning and explicit ownership. Be especially suspicious when restart recovery needs to reconstruct an in-memory protocol between multiple components.

## Rank Structural Findings

Prioritize findings that can remove an abstraction, duplicated state, significant code, an entire class of edge cases, or cross-component coupling. Deprioritize naming, formatting, minor nullability, and generic best practices unless they reveal a structural problem.

Each major finding must include:

- **Finding** — the structural problem;
- **Evidence** — concrete files, symbols, flows, and callers;
- **Why it likely exists** — evidence-backed design pressure, clearly separated from speculation;
- **Simpler model** — the smallest plausible alternative;
- **Contract change** — any guarantee lost or changed;
- **Consequences** — what becomes simpler and what capability or flexibility is lost;
- **Confidence** — high, medium, or low, with the main uncertainty.

Do not inflate the report with weak findings. A short list of well-supported structural changes is better than an architectural lint dump.

## Deliver the Review

Produce, in this order:

1. **Scope and coverage** — what was and was not inspected.
2. **Current conceptual model** — a compact verified description of the system.
3. **Highest-value findings** — ranked by structural value, not ease of implementation.
4. **Things to try deleting** — candidate layers, state, interfaces, adapters, or subsystems, and what must become true to remove each.
5. **Proposed conceptual model** — fewer concepts and a clear source of truth.
6. **Incremental migration path** — reversible steps with validation points; avoid a big-bang rewrite.
7. **Do not change** — apparently complex parts that protect verified invariants.
8. **Open questions** — missing product requirements or evidence that materially affect recommendations.

Use diagrams only when ownership, flow, or state transitions would be materially clearer than prose. Be opinionated, but label inference and uncertainty. Do not propose fashionable frameworks or abstractions without existing concrete demand.

The target is not theoretical elegance. It is the minimum architecture that safely implements the product's actual requirements.

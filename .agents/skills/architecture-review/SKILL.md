---
name: architecture-review
description: >-
  Review an existing or newly changed subsystem for unnecessary architectural
  complexity, duplicated concepts, fragile ownership, and unjustified
  contracts. Use for focused architecture reviews after non-trivial changes;
  use architecture-simplifier for a deeper repository or subsystem audit.
---

# Architecture Review

Use this skill to review existing or newly changed code for unnecessary architectural complexity, duplication, fragility, and concepts that are not justified by requirements.

Review and report. Do not modify source code, tests, configuration, build files,
generated files, or repository metadata unless the user explicitly requests an
implementation task after the review.

## Review principle

The implementation should not contain substantially more semantic concepts than the requirements need.

Look for places where implementation complexity exceeds requirement complexity.

## Review model

Establish the smallest verified model of the relevant change:

- the requirement and behavior that must remain unchanged;
- module and component responsibilities;
- ownership of mutable and durable state;
- control, data, lifecycle, and error flows;
- public, internal, persistence, and compatibility contracts;
- concurrency, ordering, and recovery assumptions.

Inspect callers, implementations, wiring, persistence, adjacent modules, and
meaningful tests. Do not infer a boundary from names or a few prominent files.
If the scope is too large for exhaustive inspection, state the coverage and
label conclusions as uncertain where appropriate.

## Challenge the change

Inspect the relevant modules independently for:

- abstractions used in only one place
- public types that can be private/package-local/internal
- factories/providers/registries/managers with a single implementation or caller
- duplicated lifecycle/state/error concepts
- wrapper layers that add naming but little behavior
- configuration with only one meaningful value
- indirection that makes ownership harder to understand
- code introduced only for speculative extensibility

Then inspect the interactions between modules for:

- duplicate representations of the same state
- multiple competing ownership models
- parallel protocols or event paths
- adapters between concepts that should be one concept
- duplicated validation or orchestration
- concepts that exist only because module boundaries were drawn poorly
- unnecessary synchronization or state propagation

For every suspicious concept, ask:

- What concrete requirement needs this concept?
- Could the requirement be expressed with an existing concept?
- Is the concept necessary for correctness, isolation, ownership, persistence, or compatibility?
- Is it only present for hypothetical flexibility?

Do not recommend helpers, base classes, strategies, factories, or broad
rewrites merely to rearrange the same complexity. Preserve complexity that
protects a verified invariant. When suggesting a weaker contract, name the
exact guarantee removed, verified consumers, new failure modes, and migration
impact.

## Findings

Report only evidence-backed findings, ranked by the amount of structure or
coupling they can remove. For each finding include:

- **Finding** — the structural problem;
- **Evidence** — concrete files, symbols, flows, and callers;
- **Requirement** — what appears to justify the current design, if anything;
- **Smallest simplification** — the narrowest viable alternative;
- **Contract and risk** — guarantees, compatibility, and failure modes that
  change;
- **Confidence** — high, medium, or low, with the key uncertainty.

Do not inflate the review with style issues or weak findings. If no meaningful
architectural issue is supported by the evidence, say so and identify the
invariants that justify the apparently complex parts.

---
name: minimal-delta
description: >-
  Choose and implement the smallest semantic and architectural change for a
  requirement that has architectural impact, crosses module boundaries, or
  appears to require a new concept. Preserve unrelated behavior and existing
  invariants.
---

# Minimal Delta

Use this skill before or during implementation when a task has architectural impact, crosses module boundaries, or appears to require a new concept.

## Goal

Satisfy the requirement with the smallest semantic and architectural delta while preserving all unrelated behavior and existing invariants.

## Procedure

### 1. Establish the current model

Identify only the parts of the existing system relevant to the requested behavior.

State briefly:

- who owns the relevant data/resource/process
- how the relevant components communicate
- where the relevant state lives
- which existing mechanism is closest to the requested behavior

Do not redesign the system at this stage.

### 2. Derive the semantic delta

Express the requirement as the smallest set of statements that must become true.

Separate:

- behavior that must become true
- behavior that must remain true

Avoid implementation terminology unless the task explicitly requires a particular mechanism.

### 3. Identify invariants

List architectural and behavioral invariants that the implementation must preserve.

Prefer invariants already encoded by the repository, tests, APIs, ownership rules, or existing conventions.

### 4. Find the nearest existing mechanism

Before creating anything new, check whether the requirement can be expressed by extending an existing:

- class or component
- protocol/message
- state machine
- lifecycle hook
- storage mechanism
- configuration mechanism
- event path
- error model

Prefer extension of an existing concept over creation of a parallel concept.

### 5. Propose the smallest implementation

Prefer, in order:

1. no structural change
2. local logic in an existing concept
3. small extension of an existing concept
4. narrow internal helper
5. broader abstraction only when unavoidable

Do not optimize for hypothetical future requirements.

### 6. Account for every new concept

For each new abstraction, type, service, module, protocol field, config option, persistent state, dependency, or public contract change, state:

- which current requirement requires it
- why an existing mechanism cannot express the same behavior

If no concrete requirement requires it, remove it.

### 7. Challenge the proposal

Before implementation, ask:

- Can a new abstraction be removed?
- Can a public API change be avoided?
- Can new state be avoided?
- Can new configuration be avoided?
- Can a new dependency be avoided?
- Can fewer modules be touched?
- Can fewer files/types be changed?
- Can an existing concept absorb the behavior?
- Is any part of the proposal only for future flexibility?

If yes, simplify before implementing.

### 8. Produce a delta statement

For non-trivial changes, summarize before coding:

```text
Current model:
...

Required delta:
...

Minimal implementation:
...

New concepts introduced:
none
```

If new concepts are introduced, list them explicitly with one-line justification each.

### 9. Implement narrowly

During implementation:

- avoid unrelated cleanup
- avoid renaming unrelated code
- avoid formatting unrelated files
- avoid moving code unless required
- preserve established patterns unless they block the requirement

### 10. Re-check after implementation

Compare the actual change against the semantic delta.

Remove anything that is not required by:

- the requested behavior
- an invariant
- necessary verification/testability

## Default stance

A new concept has a cost.

Extensibility has zero value unless required by the current task.

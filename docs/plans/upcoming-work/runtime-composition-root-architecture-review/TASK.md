# Review the Orion Runtime Composition Root

Status: todo
Depends on:
[hierarchical configuration acceptance](../../current-work/hierarchical-orion-configuration/administration-and-acceptance/TASK.md),
[key-material rotation and bootstrap acceptance](../../current-work/unified-key-material-bootstrap/rotation-recovery-and-acceptance/TASK.md),
[the Git server transport review](../git-server-transport-architecture-review/TASK.md)
Required skill: `architecture-simplifier`

Perform a read-only architecture review of `core/bootstrap`, `net/transport`,
their Dagger composition, and direct runtime lifecycle boundaries after the
configuration and key-material migrations stabilize those contracts.

## Scope

- Identify the single owner of runtime assembly, configuration activation,
  material resolution, service startup, readiness, shutdown, and failure.
- Trace one immutable configuration generation and its referenced material
  capabilities from bootstrap through transport and repository construction.
- Inspect Dagger modules, factories, provider fallbacks, state machines, event
  callbacks, and service-manager coordination for multiple sources of truth,
  hidden optional modes, or duplicated lifecycle state.
- Verify dependency direction between bootstrap policy, feature modules, and
  concrete adapters, including which layer may select implementations.
- Check ownership and close order for transports, repositories, executors,
  schedulers, key material, and reload subscriptions.
- Reuse completed module and vertical-slice reviews rather than reopening
  Git, HTTP, configuration, ACL, or key-storage internals.

## Deliverables

- Save a dated evidence-backed report under `docs/reviews/` with the current
  composition model, ranked simplifications, deletion candidates, and one
  proposed lifecycle/ownership graph.
- Create separate task-tree nodes for accepted changes; do not modify
  production code during the review.

## Completion Criteria

- The review covers cold start, disabled features, invalid configuration,
  partial startup, reload or replacement, normal shutdown, and failed shutdown.
- Every constructed long-lived resource has one visible owner and one close
  path, and every fallback or default has an explicit policy owner.
- Findings state contract impact and concrete evidence from source, tests,
  dependency graphs, and relevant history.

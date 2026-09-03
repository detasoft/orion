# Review Git Server Transport Composition

Status: todo
Depends on:
[the Git wire re-audit](../git-wire-architecture-simplification/post-simplification-review/TASK.md),
[the Git client removal re-audit](../git-client-architecture-simplification/post-simplification-removal-review/TASK.md),
[the Git interoperability matrix](../../current-work/git-interoperability-matrix/TASK.md),
[remote Git proxy bootstrap](../../current-work/remote-git-proxy-bootstrap/TASK.md)
Blocks: [the virtual-thread Git transport migration](../virtual-thread-jetty-ssh-git-transport/TASK.md)
Required skill: `architecture-simplifier`

Perform a read-only vertical architecture review of `net/git-transport` and
the Git-specific part of `net/http-core` after the current Git simplifications
and behavior coverage are complete.

## Scope

- Review native TCP, SSH, and Smart HTTP bootstrap, authentication,
  authorization, repository command context, request/response streaming,
  failure translation, and resource ownership as one server subsystem.
- Determine which behavior is shared Git server policy and which belongs in a
  transport adapter; identify duplicated orchestration and transport-specific
  semantic drift.
- Verify that one command/session owner controls repository resolution,
  cancellation, close, timeout, and producer/consumer lifecycle.
- Compare Smart HTTP and SSH behavior for advertisement, negotiation, pack
  transfer, early close, authentication failure, and logging.
- Reuse the Git parser baseline, Git client re-audit, native storage review,
  and existing `net/http-core` module review instead of repeating their
  module-local findings.
- Exclude unrelated HTTP administration, frontend, download, and ACME code.

## Deliverables

- Save a dated evidence-backed report under `docs/reviews/` with the current
  cross-transport model, ranked simplifications, retained differences, and
  safe deletion candidates.
- Create separate task-tree nodes for accepted changes; do not modify
  production code during the review.
- Define the target boundary that the later virtual-thread transport task must
  preserve rather than carrying continuation-era coordination forward.

## Completion Criteria

- Findings are supported by source, tests, dependency graphs, and relevant
  history across both modules and all three server entrypoints.
- Equivalent SSH and Smart HTTP behavior is distinguished from intentional
  transport differences.
- The virtual-thread migration has an explicit, reviewed ownership and
  lifecycle model to implement.

# Review Native Git Storage Architecture

Status: todo
Depends on:
[canonical Git object IDs](../git-wire-architecture-simplification/canonical-git-object-id/TASK.md),
[the parser/storage boundary](../git-wire-architecture-simplification/parser-storage-boundary/TASK.md),
[one repository command context](../git-wire-architecture-simplification/repository-command-context/TASK.md)
Blocks: [externalized repository storage](../externalized-repository-storage/TASK.md)
Required skill: `architecture-simplifier`

Perform a read-only architecture simplification review of
`git/git-native-storage` before extracting an external repository storage
contract. Establish the smallest coherent storage model instead of preserving
the current filesystem layout as a set of premature interfaces.

## Scope

- Review the complete production module, its tests, history, Maven graph, and
  direct repository, transport, HTTP, bootstrap, and ACL-storage consumers.
- Identify the source of truth and ownership for repositories, refs, loose
  objects, packs, indexes, quarantine data, manifests, and ref-update events.
- Determine the actual transaction, visibility, cleanup, and recovery
  boundaries for receive-pack ingestion, pack publication, object publication,
  and ref updates.
- Examine the mixed use of concrete loose stores and pack interfaces, provider
  caches, no-op implementations, and resource ownership for concepts that can
  be collapsed or made explicit.
- Check pack ingestion, pack/index reading, delta resolution, and pack building
  for duplicated parsing, validation, byte ownership, or avoidable complete
  materialization.
- Decide whether repository file load/save helpers belong to the storage
  engine or to a separate Git application/porcelain layer.
- Treat the accepted object-ID and parser/storage changes as prerequisites,
  not as new findings, and do not repeat the Git client removal audit.

## Deliverables

- Save a dated evidence-backed report under `docs/reviews/` with the current
  conceptual model, ranked findings, safe deletion candidates, contract
  changes, retained invariants, and open questions.
- State which existing filesystem guarantees are essential semantics and which
  are implementation details that an external backend must not inherit.
- Create separate task-tree nodes for accepted implementation work; do not
  change production code during this review.
- Let the later Git wire post-simplification review reuse this report and limit
  its storage coverage to cross-module integration and regression checks.

## Completion Criteria

- Every finding includes concrete source, test, dependency, or history
  evidence and distinguishes verified behavior from inference.
- The report defines one proposed ownership and publication model suitable for
  evaluating the externalized storage task.
- External storage interface extraction does not begin before this review and
  its accepted follow-up tasks are recorded.

# Git Maintenance Scheduler and Admin Operations

## Goal

Add an operational layer around native Git maintenance so operators can inspect,
schedule, approve, and run maintenance tasks safely across repositories. The
layer must support dry-runs, verification, repair, index rebuilds, pruning, and
storage cleanup without depending on JGit and without changing the repository
format.

## Current State

- `2026-05-14-git-repository-maintenance-gc.md` defines maintenance and GC
  services, but scheduler and admin commands are still only a high-level phase.
- The metadata/provider plans already introduce repository state such as
  `REPAIR_NEEDED`, backend capabilities, and last maintenance information.
- S3-backed storage needs explicit recovery for staged or partially written data.
- Pack reuse, multi-pack summaries, reachability indexes, and projections create
  rebuildable derived state that must be refreshable by maintenance.
- Native migration and production rollout need health gates before repositories
  are switched away from JGit paths.

## Non-Goals

- Do not introduce a new native repository format or migration version.
- Do not redesign object deletion policy beyond invoking the GC and repair plans.
- Do not add a full web UI in this step.
- Do not perform destructive operations automatically by default.
- Do not add a production dependency on JGit.

## Core Concepts

- `GitMaintenanceTask`: a requested unit of maintenance work.
- `GitMaintenanceRun`: one execution attempt with status, timestamps, actor, and
  report reference.
- `GitMaintenanceSchedule`: per-repository or fleet-level schedule definition.
- `GitMaintenanceLease`: short-lived guard that prevents concurrent maintenance
  on the same repository.
- `GitMaintenanceReport`: immutable dry-run or execution report.
- `GitMaintenanceAction`: an action found by dry-run and optionally executed.
- `GitMaintenanceApproval`: scoped approval for destructive actions from a
  specific report.
- `GitMaintenanceAdminService`: API used by CLI, HTTP admin endpoints, and tests.
- `GitMaintenanceScheduler`: background service that picks due repositories and
  starts safe tasks.

## Task Types

- `VERIFY`: validate references, pack manifests, loose objects, indexes, and
  backend metadata.
- `DRY_RUN_GC`: compute reclaimable objects, packs, and staged data without
  mutation.
- `REPAIR_STAGED`: recover or discard incomplete staged writes when the storage
  backend proves they are safe to clean.
- `REPAIR_INDEXES`: rebuild missing or inconsistent derived indexes.
- `REBUILD_PROJECTIONS`: rebuild branch/tree/file projections from Git objects.
- `REBUILD_REACHABILITY_INDEXES`: refresh reachability acceleration data.
- `REBUILD_MULTIPACK_SUMMARY`: refresh pack selection metadata used for reuse.
- `PRUNE_ORPHANED_PACKS`: delete packs no longer reachable from repository
  metadata after approval.
- `REPACK`: build replacement packs and atomically publish them through the
  storage backend.
- `S3_CLEANUP`: clean staged uploads, abandoned multipart uploads, and stale
  temporary objects.
- `MIGRATION_HEALTH_CHECK`: verify that a repository is ready for native-only
  execution.

## Run Modes

- `DRY_RUN`: inspect and report only.
- `VERIFY_ONLY`: fail fast on corruption or missing required state.
- `REPAIR_ONLY`: run non-destructive or explicitly approved repairs.
- `REBUILD_ONLY`: recreate derived state without deleting repository data.
- `PRUNE_APPROVED`: execute only the destructive actions covered by an approval.

## Scheduler Behavior

1. Load enabled schedules and repository maintenance state.
2. Skip repositories that are disabled, migrating, already locked, or recently
   failed with active backoff.
3. Apply jitter so large fleets do not start at the same time.
4. Acquire `GitMaintenanceLease` before any run starts.
5. Enforce per-node and global concurrency limits.
6. Run `VERIFY` and `DRY_RUN_GC` by default.
7. Record skipped reasons and failed lease attempts in the run history.
8. Mark repositories as `REPAIR_NEEDED` when verification finds issues that
   require operator review.
9. Release leases on completion and expire stale leases on startup.

## Admin Operations

- List repository maintenance state and schedules.
- Create, update, pause, or resume a schedule.
- Start `VERIFY` or `DRY_RUN_GC` manually.
- Start rebuild-only tasks for projections and indexes.
- Inspect a maintenance report.
- Approve a scoped repair or prune action from a report.
- Execute approved actions.
- Cancel a queued or running task when the task supports cancellation.
- Retry a failed task.
- Mark a stale maintenance lease as expired after operator confirmation.
- Pause maintenance for a repository while preserving its schedule.

## Approval Model

Destructive actions must require a scoped approval:

- Approval references one immutable `GitMaintenanceReport`.
- Approval contains exact action identifiers and expected repository snapshots.
- Approval expires after a short configured period.
- Execution fails if refs, pack manifests, or storage generation changed since
  the report was produced.
- Broad approvals such as "delete all stale data" are not accepted.
- Approval and execution are recorded in the audit log with actor, reason, and
  action results.

## Startup Recovery

Startup recovery should be conservative:

1. Scan for stale leases from previous processes.
2. Expire only leases whose owner is known to be gone or whose timeout elapsed.
3. Run backend-specific non-destructive checks.
4. Clean only temporary data that the backend can prove is abandoned.
5. Mark repositories as `REPAIR_NEEDED` when manual review is required.
6. Never run prune or repack automatically during startup.

## Reports

Each report should contain:

- Repository id and backend.
- Task type and mode.
- Actor and trigger source.
- Start and finish timestamps.
- Ref snapshot, pack manifest snapshot, and storage generation identifiers.
- Warnings, blockers, and integrity errors.
- Proposed actions with stable identifiers.
- Executed actions and per-action results.
- Redacted paths or credentials where necessary.
- Retention deadline.

Reports are immutable once completed. Later execution creates a new report that
references the approved dry-run report.

## API Shape

Initial HTTP admin endpoints:

- `GET /admin/git/repositories/{repoId}/maintenance`
- `GET /admin/git/repositories/{repoId}/maintenance/runs`
- `POST /admin/git/repositories/{repoId}/maintenance/runs`
- `GET /admin/git/repositories/{repoId}/maintenance/reports/{reportId}`
- `POST /admin/git/repositories/{repoId}/maintenance/approvals`
- `POST /admin/git/repositories/{repoId}/maintenance/runs/{runId}/cancel`

Initial CLI commands can be thin wrappers over `GitMaintenanceAdminService`:

- `git-maintenance status <repo>`
- `git-maintenance verify <repo>`
- `git-maintenance dry-run-gc <repo>`
- `git-maintenance rebuild-indexes <repo>`
- `git-maintenance approve <repo> <report> <action...>`
- `git-maintenance execute-approved <repo> <approval>`

## Integration Points

- Metadata provider: read and update maintenance state, last run metadata, and
  `REPAIR_NEEDED` flags.
- Maintenance/GC service: execute verify, dry-run, repair, prune, and repack
  tasks.
- S3 backend: expose staged object cleanup, multipart cleanup, and storage
  generation checks.
- Pack reuse and multi-pack indexes: rebuild summaries and invalidate stale
  selection metadata.
- Reachability indexes: rebuild derived indexes and verify consistency.
- Projection storage: rebuild branch/tree/file projections from native objects.
- Migration rollout: block native-only migration when health checks fail.
- Events and audit: emit run, approval, execution, and failure events.
- Secret reference resolver: ensure reports and logs never expose credentials.

## Implementation Plan

### Phase 1: Models and Reports

- Add model classes for tasks, runs, reports, actions, approvals, schedules, and
  leases.
- Add serialization tests for report and approval payloads.
- Add stable action identifiers derived from report id and action content.

### Phase 2: Run Store and Leases

- Implement a local run store behind an interface.
- Add repository-scoped lease acquisition, renewal, release, and stale lease
  expiration.
- Test concurrent acquisition and stale lease recovery.

### Phase 3: Admin Dry-Run and Verify

- Implement `GitMaintenanceAdminService` for manual `VERIFY` and `DRY_RUN_GC`.
- Return immutable reports and persist run history.
- Test that dry-run does not mutate repository storage or metadata.

### Phase 4: Scheduler Loop

- Implement schedule loading, jitter, backoff, concurrency limits, and skipped
  reasons.
- Add pause/resume behavior for repository maintenance.
- Test due-run selection and disabled repository skipping.

### Phase 5: Scoped Approval

- Add approval creation from a completed report.
- Validate action ids, report immutability, expiry, actor, and repository
  snapshot.
- Test that execution is refused when refs or pack manifests changed.

### Phase 6: Startup Recovery

- Add startup scan for stale leases and backend-proven temporary cleanup.
- Mark repositories as `REPAIR_NEEDED` for ambiguous states.
- Test that startup never runs prune or repack.

### Phase 7: Rebuild Operations

- Wire rebuild-only tasks for projections, reachability indexes, and multi-pack
  summaries.
- Make rebuilds idempotent and safe to retry after interruption.
- Test missing-index and stale-index recovery.

### Phase 8: Approved Prune and Repack

- Execute only actions covered by a valid approval.
- Re-check repository snapshots before mutation.
- Record per-action results in a new execution report.
- Test partial failure handling and retry behavior.

### Phase 9: S3 Cleanup

- Add S3-specific cleanup actions for abandoned multipart uploads and staged
  objects.
- Require dry-run reports and scoped approvals for destructive cleanup.
- Test idempotent cleanup with missing or already-deleted temporary objects.

### Phase 10: Migration Health Gates

- Add `MIGRATION_HEALTH_CHECK` task.
- Block migration when verification fails, required indexes are missing, or a
  repository is marked `REPAIR_NEEDED`.
- Test healthy, repair-needed, and missing-index cases.

### Phase 11: Observability and Audit

- Emit metrics for scheduled runs, manual runs, skipped runs, failures,
  approvals, and executed actions.
- Emit audit events for manual operations and approval execution.
- Redact repository credentials and hidden refs where policy requires it.

### Phase 12: Retention

- Add configurable retention for reports, run records, and audit references.
- Preserve reports referenced by active approvals until approval expiry.
- Test retention without deleting reports required for pending execution.

## Verification Plan

- Unit tests for models, serialization, approvals, and snapshot validation.
- Unit tests for lease acquisition, renewal, release, and stale lease handling.
- Scheduler tests for jitter, backoff, concurrency, disabled repositories, and
  skipped reasons.
- Admin service tests proving dry-run and verify are non-mutating.
- Repair tests proving rebuild-only tasks are idempotent.
- Approval tests proving destructive actions fail when repository snapshots
  changed.
- Startup recovery tests proving only non-destructive cleanup runs automatically.
- S3 cleanup tests for abandoned multipart uploads and already-cleaned staged
  objects.
- Audit/redaction tests proving reports and logs do not expose credentials.
- Production dependency check confirming the path does not use JGit.

## Acceptance Criteria

- Operators can list maintenance state and run history for a repository.
- Operators can manually run verification and dry-run GC.
- Scheduled verification and dry-run maintenance can run with leases and
  concurrency limits.
- Destructive repair, prune, and cleanup actions require scoped approval from a
  dry-run report.
- Startup recovery is conservative and never performs hidden destructive work.
- Rebuild tasks can recreate derived indexes and projections without changing
  repository object data.
- Migration health checks can block unsafe native-only rollout.
- Reports and audit events are persisted, redacted, and retained according to
  configuration.

## Open Questions

- Should the CLI be implemented first, or should it wait for HTTP admin endpoints?
- What is the default report retention period for small and large installations?
- Should schedules be stored globally, per repository, or both?
- Do multi-node deployments need a shared lease backend in the first iteration?
- Which repair actions should require two-person approval in production?

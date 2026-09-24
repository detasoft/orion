# Load and Activate Native Git Configuration Snapshots

Status: todo
- Owner: codex, session 01a0d010-05d8-7b91-a154-45200daf87f0, branch `codex/native-git-config-snapshots-01a0d010`,
  worktree `.worktrees/native-git-config-snapshots-01a0d010`, started 2026-09-24 20:21 Europe/Amsterdam.

Extend the restored internal configuration repository by loading `orion.xml`
from its configured commit and publishing it as one immutable, revisioned
desired-state snapshot.

## Scope

- Identify snapshots by native Git commit id and validate the complete document.
- Open the material store and versioned `orion.xml` snapshot concurrently as
  independent bootstrap inputs; do not publish either as active configuration
  on its own.
- Validate every referenced material alias and decrypt secrets only after both
  inputs are available. A missing or invalid material reference must leave the
  last valid configuration active.
- Publish one immutable desired-state snapshot, including the system ACL, only
  after the complete input pair is valid; otherwise retain the last valid snapshot.
- Compare old and new configuration values. Update only changed parts with an
  existing safe selective mechanism. Read-through consumers observe the new
  snapshot without restart; unchanged ACL state is not reloaded. Keep live proxy
  and transport/TLS instances running when their settings change, pending a
  separately designed selective reload mechanism.
- Reload on accepted configuration ref updates without depending on public Git
  transports.
- Test both bootstrap completion orders, missing referenced material, invalid
  commits, rollback, and restart.

## Design

- Reuse the native configuration storage's commit-id revision, pinned file
  snapshot, and accepted-ref change subscription. Validation and publication
  must use the same loaded commit even if the ref moves during preparation.
- Open the material store and configuration input concurrently during bootstrap,
  then join them before validating or publishing configuration. Preserve the
  existing default-configuration creation behavior when the configured ref is
  absent and default creation is enabled.
- Reuse `OrionDesiredState` as the single published document and revision. The
  system ACL is derived from that document instead of held in a second mutable
  copy. Validate the complete parsed document, every configured material alias
  and version for its intended purpose, and all encrypted secret envelopes
  against the opened material before publishing.
- Compare document sections by value. Consumers already reading desired state
  per operation need no reload. Do not call lifecycle or proxy activation hooks
  without a safe selective apply and rollback path. The user confirmed on
  2026-09-24 that unsupported live reloads stay outside this task.

## Implementation plan

1. Trace bootstrap source resolution, native snapshot loading, material lookup,
   document parsing, reload publication, and current subsystem consumers.
2. Prepare the pinned configuration and material inputs concurrently, validate
   them as one candidate, and publish through the existing desired-state owner.
3. Remove duplicate ACL publication and read ACL from the validated document;
   skip unnecessary updates for unchanged sections using existing mechanisms.
4. Extend focused and lifecycle coverage for input order, missing references,
   invalid commit, rollback, restart, revision, and selective behavior, then run
   the complete suite.

## Acceptance

- A successful load exposes one document, system ACL, and native commit revision
  from the same commit. Neither bootstrap input alone can activate configuration.
- Invalid XML, secret envelopes, or material references keep the previous valid
  snapshot and ACL active. Initial activation fails if no valid pair exists.
- A ref update, rollback, or restart loads the intended commit independently of
  public Git transport. Default creation still yields a committed first snapshot.
- Equivalent unchanged sections cause no unnecessary runtime reload. Read-through
  consumers see the new snapshot; live proxy and transport/TLS instances are not
  restarted by this task.

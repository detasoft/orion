# Load and Activate Native Git Configuration Snapshots

Status: todo
- Owner: codex, session 01a0d010-05d8-7b91-a154-45200daf87f0, branch `codex/native-git-config-snapshots-01a0d010`,
  worktree `.worktrees/native-git-config-snapshots-01a0d010`, started 2026-09-24 17:26 Europe/Amsterdam.

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
- Atomically replace all subsystem projections only after the complete input
  pair is valid, or retain the last valid snapshot.
- Reload on accepted configuration ref updates without depending on public Git
  transports.
- Test both bootstrap completion orders, missing referenced material, invalid
  commits, rollback, and restart.

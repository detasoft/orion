# Load and Activate Native Git Configuration Snapshots

Status: todo

Extend the restored internal configuration repository by loading `orion.xml`
from its configured commit and publishing it as one immutable, revisioned
desired-state snapshot.

## Scope

- Identify snapshots by native Git commit id and validate the complete document.
- Decrypt secrets only after the material-store side of the bootstrap barrier
  is ready.
- Atomically replace all subsystem projections or retain the last valid snapshot.
- Reload on accepted configuration ref updates without depending on public Git
  transports.
- Test both bootstrap completion orders, invalid commits, rollback, and restart.

# Upcoming Work

Status: active
This queue tracks upcoming high-level implementation work. Traverse its numbered
files and directories in local numeric order, respecting dependencies and claims.
See the [dependency and coordination audit](../2026-09-09-task-stream-order.md).
Move a task to current work only when starting it. A prerequisite here may need
to run before a dependent current task.

## Coordination

### Material and configuration foundation

Characterize Local storage and remove verified dead helpers before changing
storage contracts; complete this foundation before hierarchical authorization.

### Git protocol, client, storage, and transports

Start with canonical object IDs in the wire branch and single-session request
planning in the client branch. The storage review precedes the final wire
re-audit; external storage and virtual-thread migration have separate gates.

### Remote Git and integration baseline

Recheck fixture failures after proxy integration. GitHub must reuse the external
repository synchronization path; reconcile its scope before implementation.

### Operator interfaces and HTTP

HTTP invocation precedes typed matching. Recheck the PTY failure after the
occupied terminal-administration task before making overlapping fixes.

### Acceptance reviews

These follow their named implementation/acceptance prerequisites; they do not
block independent foundation work.

### Platform follow-up and deferred investigation

macOS inspection needs a decision on cooperative versus forced recovery. The
control queue remains deferred until measured demand justifies it.

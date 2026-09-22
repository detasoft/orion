# Module Review: net/frontend/ui

## 3. Older terminal journals lack the initial PTY dimensions

**Problem.** Replay a previously recorded session whose journal contains no initial PTY_RESIZE.
The browser starts at 80x24 even if the process started at 120x40 or 160x50. Output before the first
recorded resize can wrap incorrectly; a session without resize events never corrects its geometry.

**Sources.** [Terminal construction](src/components/SessionTerminal.vue),
[resize replay](src/lib/session-terminal.js),
[session startup and metadata](../../../session-host/src/platform/unix.rs),
and [records-only HTTP response](../../http-core/src/main/java/pro/deta/orion/transport/http/SessionEventsRoute.java).
Initial geometry exists in native metadata but is not delivered through this replay endpoint.

**Documented behavior and contract.** [README](README.md) promises historical PTY replay.
Replay requires the geometry in force when output was produced. Opening the viewer must not resize
the running process. New sessions record initial PTY_RESIZE before output; this finding concerns
older journals without that event only.

**Minimal repair.** Decide whether old-session replay must recover initial dimensions from existing
native metadata, then provide them before the first output through the existing session data path.
Historical-session repair is outside the approved new-session change.

**Alternatives and consequences.** Metadata delivery requires cross-module work. Hard-coding dimensions
cannot cover arbitrary initial sizes, and resizing the live PTY cannot reconstruct historical output.

**Confidence.** High for old journals without initial geometry; the historical-session support policy
remains undecided.

**Priority signals.** Importance medium for affected historical recordings; repair ease medium-to-low
because initial metadata must reach the replay client across module boundaries.

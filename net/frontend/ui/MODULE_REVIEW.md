# Module Review: net/frontend/ui

## 3. Historical terminal replay lacks the initial PTY dimensions

**Problem.** Open a session started at 120x40, or the native CLI default 160x50, before its first resize event.
xterm starts at its default 80x24. The reader learns geometry only from PTY_RESIZE, while native startup stores
initial dimensions solely in metadata. Output wrapping and cursor/screen operations use the wrong geometry;
a session without resize events never corrects it.

**Sources.** [Terminal construction](src/components/SessionTerminal.vue#L49),
[resize replay](src/lib/session-terminal.js#L85),
[native PTY startup](../../../session-host/src/platform/unix.rs#L342),
[initial metadata](../../../session-host/src/platform/unix.rs#L468),
[resize event production](../../../session-host/src/platform/unix.rs#L1486),
[CLI defaults](../../../session-host/src/main.rs#L18),
[records-only HTTP response](../../http-core/src/main/java/pro/deta/orion/transport/http/SessionEventsRoute.java#L96),
and [replay tests](src/lib/session-terminal.test.js). Tests exercise explicit resize events, not initial
non-default geometry before any resize.

**Documented behavior.** [README](README.md#L13) promises historical PTY replay;
[the native-session plan](../../../docs/plans/tasks/05_native-session-host/TASK.md#L226) requires xterm replay.
The CLI explicitly supports different initial dimensions.

**Contract.** Replay needs the dimensions in force when output was produced. Viewing a session must not resize
the live process merely to accommodate an incidental browser default.

**Minimal repair.** Supply authoritative initial geometry through the existing session data path before first
output. For new journals, an initial existing PTY_RESIZE record is the smallest apparent change; validate
native ordering and all consumers before adopting it. Verify output with wrapping/cursor movement before the
first user resize. Decide separately how already recorded sessions obtain their historical initial geometry.

**Alternatives and consequences.** Transporting existing native metadata can support old sessions but requires
additional cross-module API work; central descriptors lack that field. Hard-coding 160x50 fails other valid
sizes. Resizing the live PTY cannot reconstruct past screen operations. An initial event needs no new event
type, but cannot retroactively repair old journals.

**Confidence.** High in the missing geometry; repair ownership and historical-session handling need a decision.

**Priority signals.** Importance: medium, affecting ordinary/default-size replay. Repair ease: medium-to-low,
because the host, journal and browser must share the initial-geometry contract.

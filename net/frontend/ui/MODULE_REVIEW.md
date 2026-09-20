# Module Review: net/frontend/ui

## 1. Pending repository creation can overwrite replacement authentication state

**Problem.** Start creation, close its enabled dialog, then replace or remove the saved token before the POST
finishes. Old success adds repository/activity data to the replacement connection; old 401/403 clears its
new credentials. Clearing credentials also leaves connectionAttempt unchanged, so pending verification can
subsequently mark the UI connected without a token.

**Sources.** [Creation completion](src/App.vue#L193), [credential clearing](src/App.vue#L113),
[settings replacement](src/App.vue#L266), [dialog cancellation](src/App.vue#L576),
[existing mutation ownership guard](src/components/RemoteAliases.vue#L75),
and [credential state tests](src/App.test.js#L151). Current creation tests do not overlap replacement settings
with delayed success or authorization failure.

**Documented behavior.** [README](README.md) describes authenticated, session-scoped UI state. No explicit race
contract was found; existing connection-attempt and RemoteAliases guards establish the ownership convention.

**Contract.** An earlier connection's response must not change the current connection's credentials or data.
The already submitted server mutation may finish; ignoring its stale UI completion does not cancel it.

**Minimal repair.** Reuse the existing connection generation/client identity to guard creation success, failure
and cleanup. Invalidate pending verification when clearing credentials. Add deferred-response component cases
for old success and old 401/403 after token replacement/removal.

**Alternatives and consequences.** Disabling settings until creation finishes adds a user restriction.
Aborting fetch alone cannot establish server cancellation or eliminate completion races. No new state owner,
service, wire format or persistent state is needed.

**Confidence.** High from the unguarded asynchronous paths; no browser reproduction was run.

**Priority signals.** Importance: medium, because a slow request can erase valid replacement credentials or
mix connection data. Repair ease: high, using the existing ownership mechanism locally.

## 2. Terminal command authorization failures bypass credential cleanup

**Problem.** After opening a terminal, let its token expire or be revoked. A command POST or command-status
GET returning 401/403 becomes an ordinary input-paused string. App receives no authorization-error event,
keeps the rejected token in session storage and continues displaying authenticated connection state.

**Sources.** [HTTP status preservation](src/lib/orion-api.js#L39),
[status failure handling](src/lib/session-commands.js#L36),
[submission failure handling](src/lib/session-commands.js#L54),
[command callback versus stream authorization handling](src/components/SessionTerminal.vue#L52),
[owning App listener](src/App.vue#L537), [command behavior tests](src/lib/session-commands.test.js),
and [terminal tests](src/components/SessionTerminal.test.js). The expired-credentials component case covers
only event-stream failure; command tests cover ambiguity and delivery failures without 401/403.

**Documented behavior.** [README](README.md) requires pausing input for failed or uncertain commands. Existing
[App tests](src/App.test.js#L262) establish rejected-credential cleanup; no command-specific exception is documented.

**Contract.** Explicit credential rejection from either terminal HTTP path must reach the existing credential
owner. Preserve ordinary failure pauses and never automatically resend commands with uncertain delivery.

**Minimal repair.** Preserve authorization information in the existing failure callback and emit the existing
authorization-error event for command submission/status 401/403. Cover both request types and ordinary failures.

**Alternatives and consequences.** A new global authentication store/interceptor is unnecessary. Retaining the
terminal after rejection would require a product decision inconsistent with current stream-auth cleanup.
This repair changes local error propagation, not the server command or replay contract.

**Confidence.** High in lost status propagation; no evidence was found for intentionally different policies.

**Priority signals.** Importance: medium, because explicit authentication rejection leaves stale credentials
and misleading state. Repair ease: high through existing callbacks and events.

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

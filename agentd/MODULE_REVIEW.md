# Module Review: `agentd`

### 1. The manual terminal lane orders receipts but not input effects

**Problem.** A paste spanning multiple 4096-byte reads becomes separate native connections. The lane advances
after `RECEIVED`, but the host sends that admission receipt before acquiring its effect mutex. If the first
handler is descheduled after its receipt, a later handler can write the next input chunk first. Input and resize
effects can likewise be reordered despite sequential submission by one terminal attachment.

**Sources.** [`LocalTerminalAttacher.readInput`](src/main/java/pro/deta/orion/agentd/terminal/LocalTerminalAttacher.java#L195)
chunks input; its [`ManualLane.run`](src/main/java/pro/deta/orion/agentd/terminal/LocalTerminalAttacher.java#L328)
waits only for admission. [`UnixDomainControlTransport.exchange`](src/main/java/pro/deta/orion/agentd/session/UnixDomainControlTransport.java#L19)
opens a socket per request. Native [`handle_operation`](../session-host/src/platform/unix.rs#L1290)
sends the receipt before the ordinary-effect lock, whereas
[`serve_connection`](../session-host/src/platform/unix.rs#L1081) completes each operation before reading another
frame on the same connection. [`LocalTerminalAttacherTest`](src/test/java/pro/deta/orion/agentd/terminal/LocalTerminalAttacherTest.java#L66)
checks submission order against a fake sender, not competing native effect execution.

**Documented behavior.** The [terminal interaction contract](../docs/plans/tasks/04_agentd/07_local-terminal/TASK.md#L23)
requires one bounded manual lane. The [native protocol](../session-host/protocol/README.md#L254) explicitly
does not promise FIFO across connections; `RECEIVED` is not effect completion.

**Contract.** Forward one terminal input stream in byte order, preserve input/resize lane order, and never retry
ambiguous manual input. The host's weaker cross-connection contract need not change.

**Minimal repair.** Let the existing manual lane own one persistent native connection, using the host's
sequential processing on that connection. Retain operation deadlines and bounded pending input; close the
connection on detach or failure. Cover multiple input chunks and intervening resize against actual native
processing, including a delayed earlier effect.

**Alternatives and consequences.** Waiting for journal results adds cross-channel coordination and manual
correlation requirements. Global native FIFO changes an expressly weaker contract for unrelated clients.
A lane-owned connection needs lifecycle handling but no new wire fields, persistent state, or replay policy.

**Confidence.** High: the scheduling interleaving is supported by both implementations. No runtime reproduction
was run, so its observed frequency is unknown.

**Priority signals.** Importance: high, because normal pasted input can reach the child in the wrong order.
Repair ease: medium, requiring connection ownership and behavioral coverage across the Java/native boundary.

### 2. A stalled control write can suspend heartbeats and reconnect indefinitely

**Problem.** If the peer exhausts the `/agent/control` send window without replenishing it, a heartbeat write
can remain pending while other HTTP/2 traffic keeps the physical connection active. The next heartbeat is
scheduled only when the current write completes. No deadline fails that outstanding write, so AgentD can
remain locally online without progressing heartbeats or initiating reconnect.

**Sources.** [`AgentControlService.sendHeartbeat`](src/main/java/pro/deta/orion/agentd/core/AgentControlService.java#L594)
advances through the send completion; [`ControlConnectionLoop.scheduleHeartbeat`](src/main/java/pro/deta/orion/agentd/core/ControlConnectionLoop.java#L155)
schedules one invocation at a time. [`JettyHttp2Transport`](src/main/java/pro/deta/orion/agentd/transport/JettyHttp2Transport.java#L83)
sets a connect timeout but no control-write deadline, and
[`write`](src/main/java/pro/deta/orion/agentd/transport/JettyHttp2Transport.java#L365) depends on Jetty's callback.
The configured Jetty 12.0.12 defaults have no stream idle timeout; its physical idle timeout does not expire
while other traffic continues. The existing
[`flowControlledSessionDoesNotBlockControlOrAnotherSession`](src/test/java/pro/deta/orion/agentd/transport/JettyHttp2LivePeerIT.java#L678)
test demonstrates pending flow-controlled writes until reset, but covers the reverse isolation direction.
[`AgentControlServiceTest`](src/test/java/pro/deta/orion/agentd/core/AgentControlServiceTest.java#L338)
covers successful heartbeat/reconnect progression, not a permanently pending heartbeat send.

**Documented behavior.** The [control runtime documentation](README.md#L58) promises periodic heartbeats and
bounded-backoff reconnect. No particular write deadline is documented.

**Contract.** Bound detection of unusable control connectivity even when the physical connection remains busy.
Preserve native sessions and prevent a stale timeout from closing a replacement connection. A fully idle
physical connection is already covered by Jetty's idle timeout and is not the trigger here.

**Minimal repair.** Use the existing control scheduler to bound the outstanding write and retire its transport
through the existing reconnect path on expiry. Test a stalled control stream with continuing session traffic
and a late completion after replacement.

**Alternatives and consequences.** A transport-native control-stream deadline is smaller if its semantics
actually bound the outstanding write despite incoming traffic. Merely setting a physical idle timeout is
insufficient. A thread per timed I/O call violates the [blocking review rules](../docs/reviews/RULES.md).
The timeout value remains a policy choice; no new service or persistent state is necessary.

**Confidence.** High on the missing bound and callback dependency; the complete failure scenario was not rerun.

**Priority signals.** Importance: high for recovery under partial transport failure. Repair ease: medium,
because cancellation, connection identity, and reconnect must remain consistent.

# Module Review: `agentd`

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

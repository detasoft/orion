# Module Review: `session-host`

## 1. Idle control connections retain unbounded native workers

**Problem.** Every accepted control connection gets a detached native thread. A client that sends no frame,
or only part of one, can retain the thread and socket indefinitely. The command admission limit does not
bound connections waiting to submit a command.

**Sources.** [Accept loop](src/platform/unix.rs), `spawn_accept_loop`, and
[frame reads](src/host.rs), `read_control_frame`, called from `serve_connection`.
AgentD's [persistent terminal connection](../agentd/src/main/java/pro/deta/orion/agentd/terminal/LocalTerminalAttacher.java)
bounds that lane's connection use, while other clients remain unbounded.

**Documented behavior.** The [protocol](protocol/README.md) specifies command capacity and reserves
termination admission, but does not specify connection capacity or idle/partial-frame deadlines.

**Contract.** Preserve persistent control connections and access to termination during blocked input.
Connection limits and timeout behavior remain unspecified and require a decision.

**Minimal repair.** Bound resources retained by idle or incomplete connections through the existing transport,
while keeping termination reachable. Cover idle clients, partial frames, and termination under saturation.

**Alternatives and consequences.** A global connection cap can block termination. An idle timeout changes
persistent-connection behavior and requires reconnect handling. A worker pool alone can leave every worker
occupied by idle clients.

**Confidence.** High in resource growth; production exhaustion frequency and appropriate limits are unknown.

**Priority signals.** Importance: medium under sustained connection growth. Repair ease: medium to low because
connection admission and termination availability must be designed together.

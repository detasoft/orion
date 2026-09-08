# Maintain Reconnect and Heartbeat

Status: todo
Depends on: ../handshake-and-authentication/TASK.md
Server contract: [Connection health](../../../agent-session-server/control-and-registries/connection-ownership-and-health/TASK.md)

Maintain the live authenticated control connection through transient transport
failures and report liveness independently of local session work.

## Scope

- Add bounded exponential reconnect backoff with jitter using the existing
  transport's reusable connections. Reset backoff after an agreed stable period.
- Handle control disconnect, reset, and `GO_AWAY` through one reconnect path;
  isolate session-stream failures and ignore callbacks from superseded attempts.
- Send identity-bound heartbeats at the agreed interval after authentication;
  stop the old connection's heartbeat before replacing it. Use the existing
  `WELCOME` configuration and agreed rejection/timeout behavior.
- Keep temporary network loss distinct from process termination. Preserve the
  in-memory credential during reconnect; final shutdown cancels retries and
  timers and closes transport resources exactly once.
- Let session discovery continue while offline without coupling heartbeat or
  reconnect progress to filesystem scans, metrics, or future journal output.

## Acceptance

- Controlled-clock and real-transport tests cover failure, repeated retries,
  successful reconnect, backoff reset, heartbeat timing, and shutdown during retry.
- Session resets do not reconnect the control channel, old callbacks cannot
  disrupt the replacement, and slow local work does not delay heartbeat.

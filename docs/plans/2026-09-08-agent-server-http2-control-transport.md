# Agent Server HTTP/2 Control Transport

Task: `current-work/agent-session-server/control-and-registries/http2-control-transport`
Pool: [Server agent registration](current-work/agent-session-server/control-and-registries/TASK.md)
Protocol: [Agent protocol](../../agent-protocol/protocol/README.md)

This ordinary implementation plan is owned by the review orchestrator on
`main`. The task worker reports material gaps and waits for a committed plan
correction; it does not edit this plan in its task worktree.

## Verified Current Model

- `net/http-core` owns `JettyHTTPServer`, the HTTPS listener, TLS material,
  servlet context, route registry, and transport shutdown. HTTPS currently
  configures `SslConnectionFactory(..., "http/1.1")` and HTTP/1.1 only.
- `OrionHttpRoute.handle` already permits direct request/response streaming;
  ordinary routes share `OrionHttpRouteServlet` and the authorization filter.
- `agent-protocol` owns bounded incremental Agent message decoding and CBOR
  encoding. No new control-message representation is required.
- `agentd` opens a non-final `POST /agent/control` over TLS HTTP/2. Its
  `connect()` completes only after successful non-final response headers,
  after which `AgentControlService` sends `HELLO` and waits for `WELCOME`.
- `agent-session-server` currently owns durable session journals. Authentication,
  launch records, connection authority, and session reconciliation are later
  leaves in the same pool. Transport itself owns no durable identity state.

## Required Delta and Invariants

Add HTTP/2 support and the full-duplex Agent control endpoint to the existing
HTTPS server. Preserve its HTTP/1.1 routes, configured certificate/trust policy,
listener address, lifecycle, and existing application authorization behavior.

HTTP response acceptance is transport readiness only. Work is not authorized
until the future authentication implementation commits its credential state
and sends `WELCOME`. Do not fabricate `WELCOME`, agent identity, or successful
registration in the default production path for this transport-only leaf.

Reuse existing Agent protocol messages and decoding behavior. Keep physical
stream lifetime local to the transport; add no agent/session registry, token
store, replication cursor, retry loop, feature flag, or separate server here.

## Implementation Boundary

1. Add only the Jetty HTTP/2 and Java ALPN server dependencies needed at the
   existing `${jetty.version}`. Reuse the current TLS material configuration.
   Negotiate `h2` and preserve HTTP/1.1 on the same HTTPS listener.
2. Prefer the existing `OrionHttpRoute` direct-response path for the endpoint,
   using asynchronous servlet facilities where required. Enable async support
   for the relevant servlet/filter holders, without changing ordinary route
   execution into a new framework. If an actual Jetty limitation requires a
   different endpoint binding, report that evidence as a plan gap first.
3. Use one narrow application-handler/connection boundary for typed inbound
   messages, ordered outbound messages, and termination. It is justified by
   the concrete transport/authentication split in this pool. Keep Jetty and
   servlet details inside `net/http-core`; keep only the necessary domain
   boundary in `agent-session-server` if crossing that module boundary is
   needed. Avoid listener buses, mapper chains, and generalized stream APIs.
4. Connect the endpoint to the real server route path. Until authentication is
   implemented, its production application handler rejects attempted Agent
   registration. Transport tests may supply a real deterministic peer handler
   through the same boundary to exercise both directions. That handler is not
   a production authentication alternative. The later authentication leaf
   replaces the rejection binding rather than retaining parallel paths.
5. Keep endpoint-local state and helpers non-public unless a real module,
   lifecycle, or protocol boundary requires visibility. Treat state needed for
   one stream as owned by that stream; avoid duplicate lifecycle registries.

## HTTP and Control Semantics

- Match exactly `/agent/control` and require `POST` over HTTPS HTTP/2. Existing
  AgentD sends `HttpFields.EMPTY`, so accept requests without `Content-Type`;
  the endpoint itself selects the Agent CBOR protocol. Do not add media-type
  negotiation or a mandatory client header in this leaf. Reject invalid method
  or transport before accepting the response stream and preserve other routes.
- Emit successful response headers with no end-of-stream before waiting for
  request-body `HELLO`. Otherwise the existing AgentD and server deadlock.
- Incrementally decode bounded input without buffering an unbounded request.
  Reuse `AgentProtocolDecoder` semantic/structural failure distinctions; do not
  reimplement the CBOR parser or log message/credential payloads.
- Deliver accepted messages in order. Serialize outbound messages in order and
  bound queued bytes/items. An unwritable peer must exert backpressure or get a
  defined delivery failure; do not silently drop messages or grow memory.
- After response headers have been committed, rejection or a terminal protocol
  failure closes/aborts the control stream. Do not try to replace committed
  headers with a later HTTP authentication status or invent a new wire message.
- Define and enforce a bounded handshake deadline from stream admission until
  successful `WELCOME` delivery or rejection. A silent peer and blocked output
  must not keep the handshake alive indefinitely. The concrete completion hook
  belongs to the same narrow connection boundary.
- Use Jetty/servlet asynchronous I/O and lifecycle facilities where practical.
  One virtual thread per complete long-lived operation is allowed if needed;
  allocating a thread per I/O call solely for timeout enforcement is forbidden.
  Use a shared lifecycle-owned scheduler or native cancellation that closes
  the underlying operation for deadlines that the transport cannot enforce.
- EOF, reset, peer disconnect, failed write, deadline, and server stop must
  converge on idempotent cleanup, including pending sends and timers. Do not
  permanently close shared server resources when one stream fails.
- No control I/O or application callback may leave a Jetty request/selector
  thread blocked behind a slow peer. Bound any handoff to application work.

## Validation

The implementation worker owns all Maven execution, outside the sandbox.

- Add live tests through the actual server endpoint for TLS HTTP/2 negotiation,
  response headers arriving before `HELLO`, and full-duplex ordered traffic.
- Cover fragmented and coalesced CBOR, unknown/recoverable semantic input under
  existing codec rules, structural corruption, oversize input, invalid method,
  transport, absent request headers, and production rejection without authentication.
- Exercise quiet-handshake timeout, blocked output/backpressure, peer reset,
  disconnect, and server shutdown. Verify pending work settles and resources
  are released rather than checking only a flag or a timeout exception.
- Verify HTTP/1.1 service on the same HTTPS listener and an unrelated request
  progressing while multiple control peers are slow. Reuse existing TLS test
  material and route fixtures where appropriate.
- Use `make run-test MODULE=<module> TEST='<test-locator>'` for focused Maven
  runs, `mvn verify -Pdev -T 4` for required development verification, and
  `make test` after every implementation commit per `AGENTS.md`.
- Inspect the resulting dependent-module compilation and Dagger wiring. Do not
  add a production dependency on `agentd` just to reuse its test peer.

## Review and Completion

Apply `minimal-implementation` before and during implementation, then perform
its final self-review of the changed subsystem and its HTTP/TLS/runtime
callers. Retain complexity only for verified full-duplex I/O, ordering,
boundedness, deadlines, and lifecycle ownership.

Return a committed implementation for orchestrator review. After review fixes,
the worker prepares one squashed task commit, removes the completed leaf and
its queue links, and updates next-task references. Keep this ordinary plan as
the implementation record under its stable task identifier.

Do not transfer to `main` or remove the worktree before the orchestrator's
per-task user gate. The next server task is durable agent and launch records.

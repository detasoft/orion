# Integrate and Verify the Server-Side MVP Acceptance Flow

- Owner: codex, session 01a09f52-b1d0-7522-bbed-2c6c389576b6, branch `codex/server-mvp-acceptance-b1d0`,
  worktree `.worktrees/server-mvp-acceptance-b1d0`, paused 2026-09-15 12:52 Europe/Amsterdam;
  next: finish the pending outside-sandbox frontend test, then prepare the reviewed task commit.
  Implementation and review are ready; full Maven test passed (2288 tests), and the targeted
  verify run passed (49 integration tests), both with zero failures, errors, or skips.

## Dependencies and acceptance boundary

Build on the integrated central-server registry, authenticated control,
commands, journal storage, replication services, and web terminal, including
label/instance registration (`93542ce5`) and journal byte ownership (`b912958e`).
The production AgentD runtime and native session-host provide the counterpart.
The control-plane architecture review in `08_control-plane-architecture-review.md`
follows this task and AgentD acceptance; it is not a prerequisite of this task.

Use a local macOS/Linux acceptance environment with a real native session-host,
production AgentD assembly, the main HTTPS/HTTP2 server path, durable server
storage, and the existing terminal event consumer. Register the agent and issue
startup authorization through existing server APIs; start a session through
the existing command service. Do not require a new administration UI or an SSH
fleet for this server-side acceptance.

Remote-machine administration and packaged SSH deployment remain in 09/01.
AgentD packaging and its broader platform matrix remain in 04/08. Do not make
either whole acceptance task a dependency merely to exercise the existing local
runtime. Windows support, journal continuity proofs, local-session deletion,
semantic projections, production object storage, and clustering remain outside
this task.

## Required result

An authenticated AgentD must use the main server endpoint to start and control
a native session, replicate its journal, and expose usable history and live
events to the web terminal. The same path must preserve committed history and
resume correctly after connection loss, AgentD replacement, and server restart.

## Design and preserved invariants

- Compose `/agent/session/{sessionId}` into the production HTTP server alongside
  `/agent/control` and the existing client event/command routes. Use the
  existing replication service and storage as the canonical journal path.
- Bind replication authorization to the authenticated control context of the
  same physical HTTP/2 connection. A label lookup, address match, supplied
  context, or test-only accessor must not substitute for that binding.
- Retain the existing registry as the authority for label/instance ownership.
  Reject pre-handshake, foreign-label, replaced-instance, and stale-connection
  streams, including delayed operations on streams already open at replacement.
  Drain and release connection/stream resources during reset and shutdown.
- Preserve TLS validation, credential lifetimes, one-use startup permits,
  instance-bound reconnect, and the target-only storage model without migration.
- Keep SessionId, EventId, command identity and operationSequence semantics.
  Command delivery alone is not completion; journal evidence confirms effects.
- Derive resume and retention acknowledgement solely from durable server data.
  Preserve raw bytes and unknown events; accept ordered, nonconsecutive EventIds.
  Identical retries are harmless, and conflicting bytes never alter history.
- Publish live events only after durable append. A slow or corrupt session must
  not prevent heartbeat, commands, or another session from progressing.
- Keep one production transport path. If composing the endpoint requires
  replacing its current transport adapter, update real consumers and preserve
  its behavioral tests through the replacement; retain no parallel legacy path.
- Reuse component coverage for storage failure mechanics. Add network-level
  evidence at the missing boundaries instead of duplicating every unit case.

## Implementation plan

1. Trace the main HTTPS composition in `net/http-core` and `core/bootstrap`,
   the control connection lifecycle, `AgentSessionServer`, and the low-level
   replication endpoint. Define the narrow connection-to-context binding and
   its lifetime using the existing authenticated owner.
2. Connect replication admission and streaming to that production transport.
   Exercise a real handshake plus journal upload through the main server, with
   rejection coverage for unauthenticated and foreign connection/session access.
3. Extend the existing live-peer infrastructure into a deterministic acceptance
   fixture using production AgentD and a real native host. Validate command
   effects through replicated records and HTTP replay, including the terminal
   consumer rather than a synthetic success response.
4. Add recovery, exited-session catch-up, concurrent-session progress, and
   replacement scenarios below. Reuse existing lower-level durability and
   malformed-record tests; avoid an unrelated storage or lifecycle redesign.
5. Update existing AgentD/protocol documentation with the actual server wiring,
   reproducible acceptance commands, local prerequisites, configuration, and
   explicitly deferred MVP capabilities.

## Acceptance scenarios

- Normal flow: real HELLO/WELCOME and session reconciliation, server-issued
  StartSession, input and resize, replicated command outcomes, terminal output,
  and exit. Read history/live output through the real HTTP route and verify it
  with the existing terminal decoder/renderer contract.
- Transport loss: disconnect after a known committed event, grow the local
  journal while offline, then resume from precisely the persisted server cursor.
  Compare raw records, including spaced IDs, and ensure no duplicate display.
- Server restart: stop and recreate server services over the same directories;
  reconnect the existing AgentD process and recover the cursor without carrying
  transient server state into the new instance.
- AgentD replacement: obtain fresh authorized startup credentials, preserve
  the label and native host, resume its journal, and reject old control and
  replication activity. Catch up a host that exits while disconnected.
- Concurrent progress: use at least two sessions and a backlog larger than one
  relay page; hold one stream while proving another stream and control progress.
  Use bounded waits and explicit barriers rather than throughput benchmarks.
- Data integrity: exercise identical replay and conflicting bytes through the
  composed transport. Retain evidence that an append failure or an uncommitted
  append cannot leak events to live readers or advance acknowledgement.
- Authorization/lifecycle: reject a session stream before handshake, reject
  ownership borrowed from a different connection, reject a foreign session,
  and fence already-open streams after takeover/replacement. Close the server
  with active streams and verify bounded shutdown and settled operations.

## Result verification

Run affected focused tests and the full `mvn test -Pdev -T 4` suite. Explicitly
run the applicable HTTP/AgentD acceptance tests under the Maven verify lifecycle
when they use Failsafe; a successful test phase alone does not execute `*IT`.
Record the exact selectors and actual executed tests, including existing
`JettyHTTPServerIT` control/restart coverage when its transport changes.
Run the relevant frontend tests for terminal consumption; verify the real HTTP
event bytes against the terminal consumer in the acceptance evidence. Native
host scenarios must execute on the supported local platform rather than being
silently skipped. All scenarios must be repeatable without external services or
access to another developer's running Orion instance.

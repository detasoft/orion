# Package AgentD and Verify MVP Acceptance

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: all sibling AgentD tasks and the packaged native session host

Package AgentD as a jlink runtime for server-controlled SSH launch and prove
that transport and orchestration remain independent from session execution.

## Scope

- Produce the executable jlink artifacts and document server-side machine
  configuration, detached SSH launch, local state, update, and log paths.
- Start a shell through the central-server protocol, deliver all local control
  commands, and receive ordered input, output, resize, signal, and exit events.
- Kill and restart AgentD during multiple live sessions, rediscover hosts,
  resume from the server's durable event IDs, and continue commands without
  stopping child process trees.
- Disconnect the server, verify journals continue independently, reconnect and
  catch up retained data, then force retention and verify the server records
  the exact missing range.
- Exercise a corrupt session alongside healthy sessions and verify heartbeat,
  commands, fair journal progress, credential redaction, and graceful shutdown.
- Publish the MVP support matrix, operational defaults, compatibility versions,
  diagnostics, and explicitly deferred runtime and workspace features.

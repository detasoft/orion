# Package AgentD and Verify MVP Acceptance

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: all sibling AgentD tasks and the packaged native session host

Package AgentD for supported service managers and prove that transport and
orchestration remain independent from session execution.

## Scope

- Produce the executable artifact and documented systemd, launchd, and Windows
  Service installation, configuration, credential, upgrade, and log paths.
- Start a shell through the central-server protocol, deliver all local control
  commands, and receive ordered input, output, resize, signal, and exit events.
- Kill and restart AgentD during multiple live sessions, rediscover hosts,
  resume from server timestamps, and continue commands without stopping child
  process trees.
- Disconnect the server, verify journals continue independently, reconnect and
  catch up retained data, then force retention and verify exact gap reporting.
- Exercise a corrupt session alongside healthy sessions and verify heartbeat,
  commands, fair journal progress, credential redaction, and graceful shutdown.
- Publish the MVP support matrix, operational defaults, compatibility versions,
  diagnostics, and explicitly deferred runtime and workspace features.

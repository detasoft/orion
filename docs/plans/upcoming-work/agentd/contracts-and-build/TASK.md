# Define AgentD Contracts and Build

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md

Create the initial JVM module and freeze the internal and server-facing
contracts needed by parallel AgentD work.

## Scope

- Add the executable module, lifecycle skeleton, configuration model, and
  explicit core, protocol, transport, session, journal, runtime, and platform
  package boundaries.
- Define bounded envelopes and codecs for handshake, status, commands, command
  results, session events, acknowledgements, resume, and gaps.
- Version the Agent protocol independently from the session journal format and
  reject incompatible peers without affecting local sessions.
- Define stable value types for agent, instance, connection, session, command,
  workspace, cursor, and timestamp identities.
- Publish compatibility fixtures and test valid round trips, unknown fields,
  malformed lengths, unsupported versions, and opaque event payloads.

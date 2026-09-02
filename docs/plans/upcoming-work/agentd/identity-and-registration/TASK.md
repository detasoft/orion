# Persist Identity and Implement Registration

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: ../contracts-and-build/TASK.md, ../http2-transport/TASK.md

Give each machine a stable identity and each AgentD process start a distinct
instance identity, then implement secure initial registration.

## Scope

- Atomically create, load, and validate `identity.json` with a stable
  `AgentId`; generate a fresh UUID `InstanceId` on every process start.
- Exchange a short-lived bootstrap token for a persistent machine credential
  and store it through a protected credential abstraction.
- Keep credentials out of command lines, journals, diagnostics, and ordinary
  logs; reject unsafe file permissions where the platform supports checks.
- Construct `HELLO` identity and version fields and process `WELCOME`
  connection identity and server configuration.
- Test first registration, restart identity reuse, concurrent initialization,
  corrupt state, rejected credentials, and secret redaction.

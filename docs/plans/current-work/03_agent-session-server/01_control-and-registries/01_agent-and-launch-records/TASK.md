# Persist Agent and Launch Records

Status: todo
Design: ../../../../2026-09-02-agentd-server-launched-identity-design.md
Plan: ../../../../2026-09-09-agent-server-durable-records.md

Give server-owned agent identity, launch authentication state, and historical
observations one durable owner that survives server restart.

## Boundaries

- Use one canonical, versioned record per `AgentId` and one filesystem owner;
  do not create separate registration, credential, or observation stores.
- Establish the complete record shape in the registration foundation so later
  leaves add guarded operations without introducing temporary persisted schemas
  or migration paths.
- Persist credential digests only. Plaintext credentials, protocol handling,
  connection liveness, SSH settings, and machine administration remain outside
  this composite.
- Treat recorded launch and observation state as historical evidence, never as
  proof that a physical AgentD connection is online.

## Acceptance

- Registered agents, launch generations, credential state, and observations
  recover from the single canonical record after server restart.
- Generation changes, credential transitions, and observation updates are
  guarded against stale or concurrent writers and expose indeterminate durable
  outcomes explicitly.
- Failures cannot publish a credential or launch generation that the server
  later forgets, and no second persistence owner or backend is introduced.

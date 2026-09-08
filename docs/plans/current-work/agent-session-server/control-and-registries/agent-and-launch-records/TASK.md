# Persist Agent and Launch Records

Status: todo
Depends on: completed Agent identity types and the server-launched identity design.
Design: ../../../../2026-09-02-agentd-server-launched-identity-design.md

Give server-owned agent identity and launch authentication state one durable
owner that survives server restart.

## Scope

- Select a storage mechanism using existing repository persistence patterns
  before introducing a dependency; specify atomic update and recovery behavior.
- Persist stable `AgentId`, current launch generation and `LaunchId`, launch
  state, and the permit and reconnect-token hashes, expiry, and consumption
  state required by authentication. Persist no plaintext credential.
- Atomically advance the launch generation and invalidate all previous launch
  credentials; make concurrent updates and failed writes explicit outcomes.
- Retain last-seen instance, version, capabilities, machine information, and
  observation time without restoring a physical connection as online at boot.
- Provide the durable operations needed by authentication and existing
  provisioning contracts without creating another machine-configuration store.

## Acceptance

- Tests cover initial creation, update, restart recovery, concurrent launch
  allocation, generation revocation, and failure at persistence boundaries.
- A failed update cannot publish credentials or a launch generation that the
  server will forget after restart; recorded metadata does not imply liveness.

# Persist Reconnect Credential State

Status: todo
Depends on: 02_launch-generation-and-permits.md
Design: ../../../../2026-09-02-agentd-server-launched-identity-design.md
Plan: ../../../../2026-09-09-agent-server-durable-records.md

Provide the atomic durable credential transitions needed by initial launch
authentication and later reconnects.

## Scope

- Atomically consume the expected current, unexpired launch permit and install
  the reconnect-token digest and expiry in the same record publication.
- Verify and conditionally renew only the current unexpired reconnect token;
  never revive a superseded generation or consumed permit.
- Keep digest bytes immutable and bounded behind one explicit algorithm and size
  contract. Persist or log no plaintext permit, reconnect token, or protocol
  authentication object.
- Leave credential generation, incoming-secret hashing, protocol-version
  validation, policy durations, and `WELCOME` delivery to the authentication
  leaf.

## Acceptance

- Tests cover permit expiry and replay, binding to generation and `LaunchId`,
  concurrent consumption with exactly one committed reconnect credential,
  renewal races, replacement-generation fencing, restart, and storage failures.
- No operation reports a credential usable before its complete record is
  durably published.

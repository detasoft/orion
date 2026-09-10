# Authenticate Agent Launches and Reconnects

Status: todo
Depends on: completed HTTP/2 control transport and durable agent/launch records,
whose final observation checkpoint is committed in `73ad78bb`.
- Owner: codex, session 01a08b49-d013-74c3-98ee-4a7f0ed6d032,
  branch `codex/agent-launch-auth-01a08b49`,
  worktree `.worktrees/agent-launch-auth-01a08b49`,
  started 2026-09-10 14:58 Europe/Amsterdam.

Authenticate each control stream against the server-owned launch before making
the connection available to other server operations.

## Scope

- Require an authenticated `HELLO`, validate protocol and journal versions, and
  bind its identity to the recorded agent, generation, and launch.
- Issue bounded, short-lived launch permits and atomically consume each permit
  once together with durable publication of the reconnect-token hash and expiry.
  Send `WELCOME` only after that state is committed.
- Verify reusable reconnect tokens against durable state and reject expired,
  mismatched, missing, or superseded credentials without exposing secrets.
- Specify reconnect-token lifetime and renewal, including lost `WELCOME`
  responses. If an initial permit was consumed before its token reached AgentD,
  recovery requires a fresh server-controlled launch, not permit reuse.
- Publish one authenticated connection context for the ownership task and the
  token-renewal operation needed by healthy control connections.

## Acceptance

- Tests cover initial login, token reconnect after server restart, concurrent
  permit use, expiry, identity mismatch, missing authentication, and bad versions.
- Lost responses and failed persistence preserve single-use permits and
  generation revocation; no connection becomes authenticated before commit.

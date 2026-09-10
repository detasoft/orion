# Complete Control Handshake and Authentication

Status: todo
Depends on: integrated unsupported-version repair `b7dce0ce` and completed
AgentD launch identity and HTTP/2 transport.
Server contract: launch/reconnect authentication integrated in `3e4a6156`.

Generalize the existing initial handshake so each new transport connection
authenticates the same running AgentD process before application work begins.

## Scope

- Extend the existing handshake and control service for both launch-permit and
  reconnect-token `HELLO`, preserving agent, instance, generation, and launch
  identity throughout one process lifetime.
- Give every attempt its own deadline and negotiation result. Validate the
  selected versions, configuration, and token before publishing the connection.
- Preserve the process-local reconnect token across connection teardown;
  replace or clear it only according to the agreed token and shutdown contract.
- Distinguish failures before credential delivery from ambiguous consumption
  and explicit rejection. A consumed permit with a lost initial `WELCOME`
  requires a fresh server launch; do not fall back to reusing that permit.
- Preserve the HTTP-headers-before-`HELLO` ordering and support control-message
  consumers after `WELCOME` without duplicating handshake ownership or receivers.

## Acceptance

- Tests cover initial authentication, subsequent token authentication, changed
  connection IDs with stable process identity, and stale handshake completion.
- Cover deadline expiry, invalid versions or tokens, lost `WELCOME`, rejected
  credentials, and cleanup without credential disclosure or local persistence.

# Add HTTP/2 Control Transport

Status: todo
Depends on: completed Agent protocol codecs and the existing Jetty HTTPS server.

Expose the long-lived bidirectional Agent control stream through Orion's
existing HTTPS listener, which currently configures only HTTP/1.1.

## Scope

- Enable HTTP/2 with ALPN while preserving existing HTTP/1.1 routes and TLS
  identity configuration; use the current server lifecycle and listener.
- Implement `POST /agent/control` with bounded incremental Agent protocol
  decoding, ordered output, backpressure, and deterministic stream cleanup.
- Send successful non-final HTTP response headers before waiting for `HELLO`:
  the existing AgentD sends it only after those headers arrive. Opening the
  transport does not authenticate the agent or authorize work.
- Define handshake deadlines and transport failure handling, including how
  authentication rejection closes the stream after response headers are sent.
  The authentication task owns credential validation and `WELCOME`.

## Acceptance

- Real TLS HTTP/2 tests cover bidirectional traffic, fragmented and coalesced
  CBOR, invalid requests, size limits, timeout, disconnect, and server shutdown.
- Existing HTTP/1.1 routes remain usable, and a slow control peer does not
  exhaust the server request threads or block unrelated requests.

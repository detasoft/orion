# Implement the Outbound HTTP/2 Transport

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: ../contracts-and-build/TASK.md

Connect AgentD to the central server with a disposable TLS HTTP/2 connection
and application-level logical streams.

## Scope

- Use Jetty low-level `HTTP2Client` to establish one outbound TLS connection
  and open the bidirectional `POST /agent/control` stream.
- Encode and decode incremental request and response DATA without assuming a
  message aligns with an HTTP/2 frame.
- Model logical control and session traffic independently from physical stream
  IDs and recreate streams after reconnect.
- Expose asynchronous send, receive, flow-control, close, and connection
  lifecycle signals without allowing a session queue to block control traffic.
- Test fragmented/coalesced messages, stream resets, GOAWAY, failed TLS or
  negotiation, connection replacement, and bounded outbound queues.

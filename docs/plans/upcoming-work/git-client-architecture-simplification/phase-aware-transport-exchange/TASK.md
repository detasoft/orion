# Model Explicit Git Client Exchange Phases

Status: todo
Depends on: ../single-session-request-planning/TASK.md

Replace the continuous duplex-session fiction with an exchange contract that
represents advertisement input, request output, explicit request completion,
and response input.

## Scope

- Replace `GitClientTransportSession.input()` and `output()` with the weakest
  phase-aware contract needed by upload-pack and receive-pack.
- Let TCP and SSH reuse their underlying streams across phases while Smart HTTP
  maps discovery to `GET` and the command exchange to `POST` explicitly.
- Make request completion an explicit operation; never use
  `BufferedByteOutput.flush()` as end-of-request or close.
- Stream the Smart HTTP advertisement into the wire parser under a byte limit
  instead of materializing the complete body before parsing.
- Preserve streaming POST backpressure and cancellation; retaining a bounded
  pipe is acceptable when required by the JDK HTTP client.
- Replace the old session contract directly and remove its compatibility
  machinery in the same change.

## Completion Criteria

- The same protocol-client tests run against Smart HTTP and SSH exchanges.
- A pack source may flush between writes on both transports without completing
  or truncating the request.
- Tests cover explicit request completion, response startup, early failure,
  cancellation, and idempotent close for both transports.
- Slow, chunked HTTP discovery is parsed incrementally and still enforces its
  configured size limit.
- Smart HTTP no longer needs EOF to switch implicitly from advertisement to
  command response.

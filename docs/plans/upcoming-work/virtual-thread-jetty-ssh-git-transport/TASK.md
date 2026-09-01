# Move Jetty and SSH Git Transports to Virtual Threads

Status: todo
Detailed design: ../../2026-08-20-virtual-thread-git-io-buffering-design.md

Move the Jetty HTTP and SSH Git transport paths to a blocking virtual-thread
execution model while keeping transport-specific byte adapters separate from
Git protocol parsing.

## Scope

- Implement generic blocking byte input/output contracts for virtual-thread Git
  sessions.
- Move SSH Git sessions to virtual threads with blocking socket or channel
  adapters and local compactable input buffers.
- Move Jetty Git request handling to virtual threads with blocking request-body
  and response adapters, or an internal bounded pump if Jetty requires callback
  bridging.
- Keep worker payloads as owned copied buffers in the first implementation; do
  not expose slices into compactable session input buffers.
- Preserve authentication, repository resolution, receive-pack, upload-pack,
  side-band, and protocol-v2 behavior across both transports.
- Retire continuation-specific transport glue only after SSH and Jetty parity
  tests cover the replacement paths.

## Child Tasks

- [ ] Add the virtual-thread byte input/output contracts and test adapters.
- [ ] Convert SSH Git sessions to the blocking virtual-thread transport model.
- [ ] Convert Jetty Git handlers to the blocking virtual-thread transport
  model.
- [ ] Port receive-pack and upload-pack session runners onto the shared blocking
  protocol path.
- [ ] Add SSH and HTTP parity tests for large input, backpressure, early close,
  authentication failure, and concurrent sessions.

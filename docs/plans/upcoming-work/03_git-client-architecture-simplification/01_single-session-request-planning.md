# Plan Each Request Inside One Remote Session

Status: todo

Build fetch and push requests from the advertisement read by the same remote
session that will execute them.

## Scope

- Replace the prebuilt-request `fetch` and `push` entrypoints with synchronous,
  operation-scoped request planners that receive the current advertisement.
- Read one advertisement, invoke the planner, negotiate capabilities, and
  transfer the pack within one `GitBlockingClientExecutor` operation.
- Replace standalone `discover()` directly with `lsRemote()` on the typed
  client APIs. Keep it as an explicit operation for inspecting remote refs and
  capabilities and for testing service-level connectivity, credentials, and
  authorization without transferring a pack.
- Define successful `lsRemote()` as opening the requested Git service, reading
  and validating its advertisement, and closing it cleanly; it is not merely a
  socket-level ping.
- Do not expose a live transport session or resource lifetime to the planner.
- Close the session without starting a request when planning fails.
- Update repository callers directly and remove the replaced entrypoints in
  the same change; do not add compatibility overloads.

## Completion Criteria

- Counting transport tests prove one `open` for a logical fetch and one for a
  logical push.
- Smart HTTP tests prove one `GET` plus one `POST`, while SSH tests prove one
  channel and one service invocation for the equivalent operation.
- Tests prove that request object IDs and selected capabilities come from the
  same advertisement, including a remote-state change between separate calls.
- Planner failure and missing-ref tests prove deterministic session closure and
  no pack transfer.
- Smart HTTP `lsRemote()` performs exactly one discovery `GET` and no `POST`;
  SSH authenticates, opens exactly one service channel, reads the advertisement,
  and closes without sending a fetch or push request.
- Fetch and push do not invoke `lsRemote()` internally, and production and test
  searches show no retained `discover()` compatibility entrypoint.

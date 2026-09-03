# Plan Each Request Inside One Remote Session

Status: todo

Build fetch and push requests from the advertisement read by the same remote
session that will execute them.

## Scope

- Replace the prebuilt-request `fetch` and `push` entrypoints with synchronous,
  operation-scoped request planners that receive the current advertisement.
- Read one advertisement, invoke the planner, negotiate capabilities, and
  transfer the pack within one `GitBlockingClientExecutor` operation.
- Remove standalone `discover()` from the public client API once request
  planning no longer needs it. Add a separately named `lsRemote`-style
  operation later only when an administrative consumer requires it.
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
- Production and test searches show no retained standalone-discovery consumer
  or compatibility entrypoint.

# Connect the First Web Terminal Consumer

Status: todo
Depends on: completed command service (`e29d41d6`) and historical/live event API
(`303e1915`, `ec085ade`, `4abe6ff9`; `SessionEventsRoute` follow mode verified by `SessionEventsLiveTest`).

Build the first raw-journal projection by replaying terminal events in the web
client and sending interactive commands through the server command service.

## Scope

- Feed historical and live `PTY_OUTPUT` bytes to xterm.js and apply ordered
  `PTY_RESIZE` events without interpreting terminal state on the server.
- Ignore unknown, harness, messenger, and other non-terminal events while still
  advancing the session event cursor.
- Resume browser connections after the last rendered event ID and hand off
  from historical replay to live output without loss or double rendering.
- Send input and resize operations with command IDs and surface transient
  command failures without inventing a separate terminal transaction model.
- Test initial replay, live output, resize order, reconnect, unknown events,
  binary terminal bytes, command failure, and an exited session.

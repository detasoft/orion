# Add Streaming Commands and Monitoring

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md
Depends on: ../command-core-and-exec/TASK.md,
../interactive-terminal/TASK.md

Add cancellable structured streams and use them for the first long-running
operator commands.

## Scope

- Implement streaming results with bounded buffering, backpressure,
  cancellation, disconnect handling, and one terminal channel outcome.
- Make `Ctrl-C` stop an interactive stream and restore the prompt without
  closing the SSH connection.
- Propagate SSH channel close and server shutdown to producers and release all
  subscriptions promptly.
- Implement `/audit monitor` and `/connection monitor` with per-event ACL
  filtering and terminal-width-aware rendering where applicable.
- Audit stream start, cancellation, failure, disconnect, and completion without
  recursively auditing rendered audit-event payloads.
- Test slow consumers, producer failure, cancellation races, disconnect,
  resize, authorization changes, and bounded memory use.

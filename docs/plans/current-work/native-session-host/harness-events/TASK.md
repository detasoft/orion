# Add Ordered Harness Event Ingress

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: completed contracts and build, completed journal core,
../unix-process-host/TASK.md

Allow trusted local harness producers to append structured events only through
the session host.

## Scope

- Add a generic typed-event control/API command with explicit namespace,
  payload-schema version, payload length, and size limits.
- Assign event IDs inside the same single-writer path used by PTY and process
  events.
- Reserve forward-compatible types for messages, status, tool calls/results,
  prompts, artifacts, and checkpoints without requiring all schemas in MVP.
- Reject invalid or unauthorized namespace use without corrupting or stalling
  the terminal stream.
- Test interleaving with PTY output and resize, unknown structured types,
  reconnects, invalid payloads, and concurrent producers.

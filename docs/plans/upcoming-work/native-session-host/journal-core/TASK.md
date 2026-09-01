# Implement the Journal Core and Metadata

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: ../contracts-and-build/TASK.md

Implement the single-writer logical event stream independently of PTY and IPC
details.

## Scope

- Append and read all MVP event types without changing raw payload bytes.
- Assign strictly increasing session-relative monotonic timestamps and expose
  timestamp cursors.
- Persist process, command, terminal, sandbox, state, and oldest/latest cursor
  metadata through atomic recoverable updates.
- Skip compatible unknown event types and reject unsupported framing versions
  predictably.
- Recover through the last complete record and ignore a partial active tail.
- Provide configurable durability without requiring `fsync` per output record.
- Test ordering, equal monotonic-clock samples, empty payloads, binary output,
  unknown types, restart reads, and corrupt or truncated tails.

# Implement the Windows ConPTY Host

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: ../contracts-and-build/TASK.md, ../journal-core/TASK.md,
../journal-retention/TASK.md

Provide Windows parity without forking the logical journal, lifecycle, or
control behavior.

## Scope

- Implement the terminal abstraction with ConPTY and the control transport with
  a named pipe.
- Reuse common journal, metadata, command deduplication, ordering, and process
  lifecycle code.
- Map resize, signal/termination, status, exit code, and platform errors to the
  v1 protocol semantics.
- Verify raw byte preservation and document unavoidable ConPTY behavior
  differences without normalizing the journal stream.
- Test fixture compatibility, interactive input/output, resize, reconnect,
  parent-process loss, child exit, and crash-tail recovery on Windows x86_64.

# Remove graceMillis from AgentD Termination Control

Status: todo
Parent: ../TASK.md

Update AgentD after the server and native session-host contracts remove the
unused `graceMillis` field from `TERMINATE`.

## Scope

- Remove `graceMillis` from `ControlCommand.Terminate` and the server message
  mapping used to create it.
- Encode native `TERMINATE` as only u16 mode and u16 reserved zero.
- Update AgentD codec tests and the shared native-control fixture checks.
- Verify graceful and force requests against the updated session-host protocol.

## Boundary

The server-facing protocol definition and session-host implementation own the
wire contract change. This task updates the AgentD model, translation, native
encoder, and their tests; it does not add grace-period scheduling or process
termination coordination.

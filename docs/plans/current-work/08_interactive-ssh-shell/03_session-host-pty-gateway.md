# Add the Session-Host PTY Gateway

Status: todo
Detailed plan: ../../2026-09-02-interactive-ssh-shell.md
Depends on: completed command-core-and-exec (3ed68b68),
completed interactive-terminal (1f99a9ea),
../03_agent-session-server/01_control-and-registries/TASK.md,
../03_agent-session-server/03_command-service.md,
../03_agent-session-server/04_live-event-api.md

Implement `/session/<id> attach` by relaying the current SSH PTY through the
Orion server to an existing session-host session.

## Scope

- Resolve and authorize the concrete session before opening a terminal
  subscription or forwarding any bytes.
- Relay binary terminal output, input, and resize over the existing
  server-to-session-host control protocol without adding a public host SSH port.
- Model attach and detach as SSH gateway states, not session lifecycle commands
  or required `ATTACH` and `DETACH` operations in the host domain protocol.
- Define a detach escape that preserves ordinary control characters for the
  process inside the PTY, then return to the same Orion prompt and current path.
- Support sequential attachment to multiple sessions on one SSH connection and
  clean shutdown on session exit, transport failure, disconnect, or server stop.
- Audit authorization, attach, detach, failure, and duration without logging
  terminal contents.
- Test input/output ordering, raw bytes, resize, slow consumers, detach races,
  reconnect, session exit, ACL denial, and return to the administrative shell.

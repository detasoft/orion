# Launch and Control Session Hosts

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: ../contracts-and-build/TASK.md and the native session-host process
and control contracts

Add a runtime and local-control layer that starts `session-host` without making
it or its child process depend on AgentD.

## Scope

- Define `SessionRuntime`, `SessionSpec`, launch results, and a workspace
  reference that does not assume every session uses an arbitrary existing cwd.
- Implement `NativeRuntime` validation, session-directory preparation,
  detached host launch, initialization wait, and cleanup of failed starts.
- Hide Unix sockets and Windows named pipes behind a reconnectable control
  client for input, resize, signal, terminate, and status.
- Preserve server command and input IDs through host acknowledgement and retry;
  report expected validation or delivery errors as results.
- Test successful launch, invalid policy or workspace, initialization failure,
  AgentD exit survival, control reconnect, duplicate input, and host errors.

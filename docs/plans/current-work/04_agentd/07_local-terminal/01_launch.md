# Launch a Local Session Host from AgentD

Status: todo
Design: ../../../2026-09-10-agentd-local-session-launch-design.md
Plan: ../../../2026-09-10-agentd-local-session-launch.md
Depends on: completed native runtime and local control support `2b08b1fd` and
durable native start outcomes `435e9ae1`

Add `agentd terminal start` as a launch-only local command. It starts a real
`session-host` through `NativeRuntime`, waits for durable handoff, prints the
session identity and directory, and exits without stopping the host.

## Scope

- Route `terminal start` before daemon configuration or launch-permit handling.
- Accept the host executable, state directory, optional session identity and
  working directory, followed by the child command after `--`.
- Use fixed non-interactive PTY defaults and existing environment values where
  applicable; do not acquire or modify the caller's terminal.
- Reuse `SessionSpec`, `NativeRuntime`, and `SessionLaunchResult` without a
  second launch abstraction or server runtime.
- Report invalid input separately from runtime launch failure.

## Acceptance

- A real packaged `session-host` reaches durable handoff when started through
  the AgentD command and remains alive after AgentD exits.
- Local launch does not read a launch permit, acquire the daemon process lock,
  or initialize HTTP/2 transport.
- Successful output contains the session ID and normalized session directory;
  failures have a non-zero exit code and bounded diagnostics.
- Existing daemon invocation and behavior remain unchanged.

## Boundary

Interactive attach, journal rendering, input, resize, signals, termination, and
journal acknowledgement belong to the following child task.

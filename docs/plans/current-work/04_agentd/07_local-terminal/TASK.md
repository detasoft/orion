# Run AgentD as a Local Terminal

Status: todo
Design: ../../../2026-09-04-agentd-local-terminal-design.md
Focused launch design: ../../../2026-09-10-agentd-local-session-launch-design.md

The focused design governs `01_launch.md`; the broader design governs the
interactive follow-up.

Provide a local AgentD path for starting and later attaching to an independent
`session-host` without an Orion server.

The first child delivers a launch-only vertical slice through the existing
native runtime. Interactive attachment, journal rendering, input, resize, and
manual control remain in the second child and retain their broader dependencies.

## Boundary

- Local mode does not read a server launch permit or initialize AgentD's HTTP/2
  transport.
- `session-host` and its child remain independent after the launching AgentD
  command exits.
- Server command orchestration and journal replication remain in their existing
  tasks.

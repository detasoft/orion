# Session Host Instructions

## Current Platform Limit

The root Maven build currently does not run on Windows when `session-host` is
included. `make/session-host.mk` supports only Darwin and Linux Rust bootstrap
hosts and exits before invoking Cargo for Windows host names.

Do not describe Windows as supported, enable a Windows CI job, or change the
six-target release requirement based on this limitation. A dedicated Windows
bootstrap and ConPTY task must remove this limitation before Windows builds are
enabled.

## Shutdown Ownership

`session-host` is a command proxy and process-tree executor, not the shutdown
coordinator. The server owns termination commands, grace periods, escalation,
and recovery after an AgentD restart. The host executes each admitted
operation once; an uncertain effect is not replayed by the host.

- A termination command performs one requested signal delivery and records the
  observed signal attempt in the session journal after delivery.
- `session-host` must not maintain a termination deadline, auto-escalate from
  graceful to force, or retry signal delivery. As an emergency fallback, an OS
  signal received by the host is forwarded once to the current process tree;
  this is signal passthrough, not shutdown coordination. SIGKILL and SIGSTOP
  cannot be intercepted, and SIGCHLD remains host-internal.
- The server reconstructs shutdown state from the journal. A missing signal
  record leaves delivery unknown and is not a host retry instruction;
  `PROCESS_EXITED` is the terminal process-tree fact.
- The session journal is the recovery source of truth when AgentD restarts;
  shutdown state must not depend on AgentD-only in-memory state.

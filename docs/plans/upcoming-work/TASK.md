# Upcoming Work

Status: active
Source: converted from former root task list Next section.

This task node tracks upcoming high-level implementation work. Multiple child
tasks may be ready at the same time.
See the [leaf order and cross-stream gates](../2026-09-09-task-stream-order.md).
Move a task to current work only when starting it. A prerequisite here may need
to run before a dependent current task.

## Child Tasks

### Material and configuration foundation

- [ ] [Harden ACL storage contracts](acl-storage-hardening/TASK.md)

Characterize Local storage and remove verified dead helpers before changing
storage contracts; complete this foundation before hierarchical authorization.

### Git protocol, client, storage, and transports

- [ ] [Simplify Git wire architecture](git-wire-architecture-simplification/TASK.md)
- [ ] [Simplify Git client architecture](git-client-architecture-simplification/TASK.md)
- [ ] [Refactor Git report-status to blocking streaming parsing](git-report-status-blocking-parser/TASK.md)
- [ ] [Review native Git storage architecture](git-native-storage-architecture-review/TASK.md)
- [ ] [Review Git server transport composition](git-server-transport-architecture-review/TASK.md)
- [ ] [Move Jetty and SSH Git transports to virtual threads](virtual-thread-jetty-ssh-git-transport/TASK.md)
- [ ] [Externalize repository storage](externalized-repository-storage/TASK.md)

Start with canonical object IDs in the wire branch and single-session request
planning in the client branch. The storage review precedes the final wire
re-audit; external storage and virtual-thread migration have separate gates.

### Remote Git and integration baseline

- [ ] [Repair Git configuration integration fixtures](git-configuration-integration-fixtures/TASK.md)
- [ ] [Implement GitHub commit replication](github-commit-replication/TASK.md)

Recheck fixture failures after proxy integration. GitHub must reuse the external
repository synchronization path; reconcile its scope before implementation.

### Operator interfaces and HTTP

- [ ] [Harden HTTP core contracts](http-core-hardening/TASK.md)
- [ ] [Fix the SSH PTY completion integration timeout](ssh-pty-completion-timeout/TASK.md)

HTTP invocation precedes typed matching. Recheck the PTY failure after the
occupied terminal-administration task before making overlapping fixes.

### Acceptance reviews

- [ ] [Review the Agent session control plane](agent-session-control-plane-architecture-review/TASK.md)
- [ ] [Review the Orion runtime composition root](runtime-composition-root-architecture-review/TASK.md)

These follow their named implementation/acceptance prerequisites; they do not
block independent foundation work.

### Platform follow-up and deferred investigation

- [ ] [Add safe macOS AgentD process inspection](macos-agentd-process-inspector/TASK.md)
- [ ] [Evaluate a session-host control request queue](session-host-control-request-queue/TASK.md)

macOS inspection needs a decision on cooperative versus forced recovery. The
control queue remains deferred until measured demand justifies it.

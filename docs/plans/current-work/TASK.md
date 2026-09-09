# Current Work

Status: active
Source: converted from former root task list Current section.

This task node tracks active high-level implementation work. The numbered
streams give selection priority, not a dependency between independent streams.
See the [leaf order and cross-stream gates](../2026-09-09-task-stream-order.md).

## Child Tasks

### 1. Material and configuration

- [ ] [Unify key material bootstrap and short-lived JWTs](unified-key-material-bootstrap/TASK.md)
- [ ] [Add hierarchical Orion configuration](hierarchical-orion-configuration/TASK.md)

Next: remaining key-owner migration. Configuration authorization follows the
ACL foundation in upcoming work; snapshot loading is an independent branch.

### 2. Agent control and journal delivery

- [ ] [Build the central agent session server](agent-session-server/TASK.md)
- [ ] [Build the AgentD orchestration service](agentd/TASK.md)

Next: durable server agent/launch records; AgentD handshake is independently
available. Replication follows authenticated ownership, then commands and clients.

### 3. Native session execution

- [ ] [Build the native session host for the agent harness](native-session-host/TASK.md)

Process-tree and process/PTY-control work is occupied. Coordinate source-aware
controls and Linux discovery fixes with those owners before overlapping changes.

### 4. Remote Git

- [ ] [Add transparent remote Git proxy bootstrap](remote-git-proxy-bootstrap/TASK.md)
- [ ] [Implement external Git repository synchronization](external-git-repository-sync/TASK.md)

Proxy bootstrap and primary upstream synchronization are occupied. Their
dependent configuration, UI, and filtering work follows the integrated results.

### 5. Operator interfaces and deployment

- [ ] [Add the interactive Orion SSH shell](interactive-ssh-shell/TASK.md)
- [ ] [Provision remote AgentD machines](remote-machine-provisioning/TASK.md)

Terminal administration is occupied. Session attachment needs server commands
and live events; remote deployment acceptance needs packaged AgentD/session-host.

### 6. Repository maintenance

- [ ] [Review stale branches and worktree cleanup candidates](stale-branches-and-worktrees/TASK.md)

Paused and owned. It is not the next generic implementation task.

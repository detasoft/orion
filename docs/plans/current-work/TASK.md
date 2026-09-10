# Current Work

Status: active
This queue tracks active high-level implementation work. Traverse its numbered
files and directories in local numeric order; descend recursively to the first
unclaimed, dependency-ready leaf. See the
[dependency and coordination audit](../2026-09-09-task-stream-order.md) for gates.

## Coordination

### Material and configuration

Next: remaining key-owner migration. Configuration authorization follows the
ACL foundation in upcoming work; snapshot loading is an independent branch.

### Agent control and journal delivery

Launch/reconnect authentication is integrated in `3e4a6156`. Next: server
connection ownership and health; AgentD handshake is independently available.
Replication follows authenticated ownership, then commands and clients.

### Native session execution

Linux process ownership is integrated in `c0a764d1`; real delegated-cgroup
acceptance awaits a modern validation host. LIST_PROCESSES and PTY_CLOSED can
now proceed against the integrated owner and current source-aware controls.
The preserved Linux branch retains their earlier design.

### Remote Git

Proxy bootstrap and primary upstream synchronization are occupied. Their
dependent configuration, UI, and filtering work follows the integrated results.

### Operator interfaces and deployment

Terminal administration is occupied. Session attachment needs server commands
and live events; remote deployment acceptance needs packaged AgentD/session-host.

### Repository maintenance

Paused and owned. It is not the next generic implementation task.

# Orion Tasks

Status: active

This numbered tree is the single source of truth for unfinished Orion work.
Traverse it recursively in numeric sibling order and select the first
dependency-ready executable leaf without an `Owner:` entry.

A numbered `NN_slug.md` file is an executable task. A numbered `NN_slug/`
directory is a composite described by its `TASK.md`. Each task contains its
own requirements, design decisions, implementation plan, dependencies, and
acceptance criteria; separate plan and design documents are not part of the
task model.

## Coordination

- Key material and ACL storage precede dependent hierarchical authorization.
- Authenticated Agent control ownership precedes journal replication and
  command delivery; AgentD handshake work may proceed independently.
- Native session-host lifecycle, AgentD orchestration, and server integration
  retain their explicit cross-links and acceptance gates.
- Git wire and client simplification precede their storage and transport
  architecture reviews and follow-up migrations.
- Remote Git synchronization, operator interfaces, HTTP hardening, and final
  architecture reviews follow the dependencies recorded in their leaves.

Completion removes the executable leaf and any eligible empty composite.
Completed task text is not retained as history; Git commits are the evidence.

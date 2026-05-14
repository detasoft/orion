# Build Execution and Agent Management

## Goal

Add build execution to Orion with managed agents, a queue, assignment rules,
logs, cancellation, and durable build state.

This is one of the largest roadmap areas and should remain later in the
implementation order. It touches persistence, security, repository events,
runtime isolation, logging, and external worker lifecycle.

## Current State

Orion has an internal `OrionExecutor` for application tasks, lifecycle-managed
services, Git receive events, ACL grants, HTTP admin routes, and repository
storage.

There is no build domain model, no build queue, no agent protocol, no agent
registration, no workspace manager, no build log storage, and no artifact
storage.

The existing executor is for Orion server work. It should not become the build
execution engine for untrusted or long-running user builds.

## Non-Goals

Do not run arbitrary user build commands inside the Orion server process.

Do not implement autoscaling, container orchestration, or remote provisioning in
the first version.

Do not make build execution depend on GitHub or GitLab mirroring. Local
repository events should be enough to trigger an initial build.

Do not design the build queue without a persistence and recovery story.

## Scope

Define the core build concepts:

- build configuration;
- build trigger;
- build request;
- build id;
- queue item;
- agent;
- assignment;
- checkout or source revision;
- workspace;
- build step;
- log stream;
- result status;
- artifact outputs.

Define agent management:

- registration token;
- agent id and name;
- heartbeat;
- capabilities;
- current assignment;
- offline timeout;
- graceful disconnect;
- force remove;
- protocol version.

Define build lifecycle states:

- queued;
- assigned;
- starting;
- running;
- cancel requested;
- canceled;
- failed;
- succeeded;
- timed out;
- lost agent.

## Phased Plan

Phase 1: Build model only.

Add immutable build and agent state models with tests for valid state
transitions. Do not execute commands yet.

Phase 2: Persistence and recovery.

Persist build queue, build records, agent records, and assignment records.
Define startup recovery for queued builds, running builds whose agents are
gone, and assignments that were in progress during shutdown.

Phase 3: Agent protocol skeleton.

Add authenticated agent registration, heartbeat, and polling or streaming for
assignments. Use application tokens or a dedicated agent token kind once the
token plan exists.

Phase 4: Queue and assignment.

Implement queue ordering, capability matching, assignment claiming, and
duplicate assignment prevention. Add cancellation before command execution.

Phase 5: Local safe execution prototype.

Implement a minimal agent that can run a fixed allowlisted command in a working
directory. Keep this separate from the server process. Capture stdout, stderr,
exit code, start time, end time, and timeout.

Phase 6: Git checkout and triggers.

Allow builds to reference an Orion repository revision. Add optional triggers
from `GitReceiveOrionEvent` after the queue is durable.

Phase 7: Logs and artifacts.

Store logs and artifacts through dedicated storage abstractions. This can share
concepts with the Maven repository plan, but build logs and Maven artifacts
should not be forced into the same model.

## Open Questions

Should agents connect outbound to Orion, or should Orion connect to agents?

Should the first agent protocol be HTTP polling, WebSocket, SSH, or another
transport?

What isolation is required for build commands: process only, container,
separate user, VM, or external runner?

Where should build logs and artifacts live?

Should build configuration be stored in Orion admin config, repository files,
or both?

How much of the model should be compatible with TeamCity, GitHub Actions, or
GitLab CI concepts?

## Verification

Cover at least these cases:

- build state transitions reject invalid moves;
- agent registration requires a valid token;
- heartbeat marks agents online and missing heartbeat marks them offline;
- queue assignment is atomic and does not assign one build to two agents;
- canceled queued builds are never assigned;
- cancel requested for a running build reaches the agent;
- server restart recovers queued builds and marks lost running builds
  explicitly;
- minimal agent command captures logs, exit code, timeout, and failure reason;
- Git receive event can enqueue a build only when a matching trigger exists;
- authorization separates build administration, build read, and build trigger
  operations.

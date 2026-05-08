# Application Lifecycle

Orion startup and shutdown order is defined in two layers:

1. `LifecycleFlow` defines transitions between application phases.
2. `LifecycleTaskPlan` defines named tasks inside a phase and orders them by explicit dependencies.

Lifecycle code must register named tasks through `ApplicationStateListenerRegistrar.task(...)`.

## Phase Flow

| Flow | Step | Success | Failure |
| --- | --- | --- | --- |
| STARTUP | INIT | STARTING | FAILED |
| STARTUP | STARTING | UP | FAILED |
| SHUTDOWN | UP | BEGIN_SHUTDOWN | FAILED |
| SHUTDOWN | BEGIN_SHUTDOWN | STOPPING | FAILED |
| SHUTDOWN | STOPPING | OFF | FAILED |

## Current Runtime Task Graph

`INIT` prepares global runtime state:

```text
JGIT_RUNTIME
SSH_TRANSPORT_INIT
EVENT_MANAGER after JGIT_RUNTIME
ACL_INIT after EVENT_MANAGER
GIT_BACKED_INTERNAL_STORAGE_INIT after ACL_INIT
```

`STARTING` brings storage and ACL up before any transport accepts users:

```text
REPOSITORY_STORAGE
ACL_LOAD after REPOSITORY_STORAGE
TRANSPORTS_START after ACL_LOAD
HTTP_TRANSPORT_START after TRANSPORTS_START
GIT_TRANSPORT_START after TRANSPORTS_START
SSH_TRANSPORT_START after TRANSPORTS_START
```

`STOPPING` shuts down transports before shared infrastructure:

```text
TRANSPORTS_STOP
HTTP_TRANSPORT_STOP after TRANSPORTS_STOP
GIT_TRANSPORT_STOP after TRANSPORTS_STOP
SSH_TRANSPORT_STOP after TRANSPORTS_STOP
EVENT_MANAGER_STOP after HTTP_TRANSPORT_STOP, GIT_TRANSPORT_STOP, SSH_TRANSPORT_STOP
EXECUTOR_STOP after EVENT_MANAGER_STOP
```

## Registering Tasks

Use a stable task id from `OrionLifecycleTasks`, or add a new one when introducing a new service.

```java
task(registrar, ApplicationState.STARTING, MY_SERVICE_START, this::onStart)
        .after(OrionLifecycleTasks.ACL_LOAD);
```

Use `after(...)` when the task needs another task to complete first. Each task declares its own prerequisites; if
`TRANSPORTS_START` must run after `ACL_LOAD`, that edge belongs on `TRANSPORTS_START`.
The `task(...)` helper on `OrionApplicationStageEventListener` records the listener class name as the service name for
debug service-map output. Shared barrier tasks should still have a small listener class when they are part of the
runtime graph, so the service map shows who owns the barrier.

Tasks in the same execution group start together. The next group starts only after every task callable in the current
group has completed successfully, so dependency edges are the only ordering barrier. If a task returns
`OrionStageCallResult` with futures to wait for, lifecycle execution also waits for those futures according to the task
result before moving to the next group.

## Removed Priority API

The old `register(ApplicationState, Callable)` API, `priority(...)`, and listener-level
`waitForCompletion(...)` have been removed. Integer priorities hid the reason for ordering and made inserting a new
service between existing steps error-prone. Use named task dependencies for all lifecycle code.

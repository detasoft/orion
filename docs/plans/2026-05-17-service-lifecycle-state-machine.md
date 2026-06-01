# Service Lifecycle State Machine Plan

## Goal

Introduce a reusable service-level state machine that can be defined before a
service is wired into Orion's application lifecycle.

The state machine should make lifecycle behavior explicit:

- allowed states;
- allowed actions;
- legal transitions;
- actions currently available from the current state;
- failure behavior;
- state change events.

Concrete services such as `GitNativeTransportService` should bind to this
machine through adapters instead of hardcoding lifecycle transitions inside
`onStart()` and `onStop()`. This allows multiple runtime resources, such as two
native Git listener ports, to share one centralized management model.

## Progress Update 2026-05-19

The generic utility now lives in `core/lifecycle-state-machine` instead of
`core/common`. Its current model uses:

- fixed `StateMachineDefinition.State` values, with `NEW`, `FIN`, and `ERR`
  conventions;
- stable `ActionId` values such as `START` and `STOP`;
- typed `ActionBinding<A>` handlers for payload-specific side effects;
- synchronous serialized transition execution;
- state-machine events, transition listeners, snapshots, diagnostics, and child
  propagation.

`GitNativeTransportStateMachine` now models only the native Git transport's
local `START` / `STOP` transitions. It is no longer an
`OrionApplicationStageEventListener` and no marker interface is needed for the
current runtime wiring.

`TransportLifecycleStateMachine` is the temporary aggregate bridge from the
application lifecycle to transport-local state machines. It explicitly wires the
native Git state machine as an always-present child. The child owns the native
Git enabled check and lazily resolves `Provider<GitNativeTransportService>` only
when an enabled `START` or `STOP` action needs the service. Both the child state
machine and the low-level native Git service depend on `GitTransportConfig`
instead of the full runtime configuration object. `net/transport` owns the
transport composition layer: it includes the HTTP Dagger module, registers
enabled transport services, publishes the aggregate `StateMachine`, and keeps
`core/bootstrap` dependent only on this transport composition module. Dagger
owns both `GitNativeTransportStateMachine` and `GitNativeTransportService`; the
important boundary is that only `TransportLifecycleStateMachine` is registered
as an application lifecycle listener. The aggregate owns the transport-wide
`TRANSPORT_LIFECYCLE_START` and `TRANSPORT_LIFECYCLE_STOP` task registrations;
individual `GIT_TRANSPORT_*`, `HTTP_TRANSPORT_*`, and `SSH_TRANSPORT_*`
start/stop tasks are not registered separately. The runtime module no longer
imports concrete transport services and no longer collects a Dagger set of
abstract state machines. `OrionGitTransportModule` was removed because no extra
multibinding module is needed for native Git state-machine wiring.

`StateMachine.describeStatus()` now renders a compact ASCII tree of the
aggregate and child states. It uses computed state when present and includes the
physical state in parentheses, for example `DISABLED (state=NEW)`.

`GitNativeTransportService` is no longer registered directly as an
`OrionApplicationStageEventListener`; it is the lower-level socket endpoint
implementation called by the state-machine actions.

`OrionStageCallResult` is not part of the state-machine concept. It remains an
application lifecycle API detail. The transport aggregate lifecycle registration
adapts `START` and `STOP` transition execution to lifecycle tasks and returns no
lifecycle wait result.

Aggregate startup failures intentionally allow partial running. If one endpoint
fails to start, the aggregate moves to `ERR`/`FAILED`, but endpoints that already
started remain `RUNNING`. The aggregate does not automatically roll them back;
cleanup is handled by an explicit later `STOP` from the failure state.

The old `NativeGitListenerEndpoint` name should be read as a future extraction
of the socket listener currently inside `GitNativeTransportService`. It is not a
required extra abstraction for the current single-listener runtime wiring. It
becomes useful only when the service needs multiple listener endpoints or a
classic-socket/NIO engine boundary.

## Current State

Orion already has an application-level lifecycle:

- `ApplicationState`;
- `LifecycleFlow`;
- `LifecycleFlowRunner`;
- `ApplicationStateListenerRegistrar`;
- `LifecycleTaskRegistration`;
- `OrionApplicationStageEventListener`.

That model is good for global process flow, but service internals still manage
their own state directly. For example, `GitNativeTransportService` owns socket
binding, accept loop startup, shutdown flags, and lifecycle task registration in
one class.

This makes it harder to:

- describe service-local lifecycle separately from application lifecycle;
- expose available operations for administration;
- manage multiple resources under one service;
- test lifecycle transitions without sockets or application lifecycle setup;
- reuse the same lifecycle behavior for future transport implementations.

## Non-Goals

Do not replace the application lifecycle in this step.

Do not redesign `ApplicationState` for service-local states.

Do not introduce the native Git NIO transport as part of this work.

Do not make all existing services migrate at once. Start with a small utility
and one concrete transport adapter.

## Architecture

Use three layers.

### Layer 1: Generic State Machine

The generic state machine should not know anything about Orion application
states, sockets, Git, HTTP, SSH, or Dagger.

Expected core types:

- `StateMachineDefinition<S, A>`;
- `StateTransition<S, A>`;
- `StateMachine<S, A>`;
- `StateMachineListener<S, A>`;
- `StateMachineEvent<S, A>`.

The generic machine should support:

- current state lookup;
- available action lookup;
- transition execution by action;
- rejection of invalid actions;
- serialized transitions;
- failure transition handling;
- event emission after state changes;
- optional transition metadata for diagnostics.

Example service states:

```java
enum ServiceState {
    NEW,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
```

Example service actions:

```java
enum ServiceAction {
    START,
    STOP,
    FAIL
}
```

The service definition should explicitly declare legal transitions:

- `NEW + START -> STARTING`;
- `STARTING + STARTED -> RUNNING`, if startup completion is modeled as a
  separate action;
- `RUNNING + STOP -> STOPPING`;
- `STOPPING + STOPPED -> STOPPED`, if shutdown completion is modeled as a
  separate action;
- `STARTING/RUNNING/STOPPING + FAIL -> FAILED`;
- `FAILED + STOP -> STOPPING`, when cleanup is still required after failure.

The first implementation can keep side effects synchronous and model
`START -> RUNNING` and `STOP -> STOPPED` as single guarded transitions. The
definition still has to be the single source of truth for allowed states,
allowed actions, terminal states, and available actions.

### Layer 2: Service Adapter

A service adapter maps generic lifecycle actions to concrete side effects.

For a listener endpoint:

- `START` binds the socket and starts the accept loop;
- `STOP` closes the listener and active resources;
- `FAIL` records the failure and moves to a terminal failure state.

The adapter owns resource cleanup. The state machine owns legal transitions.

Each concrete adapter must publish a hard lifecycle contract for the service it
connects:

- initial state;
- terminal states;
- transition table;
- action handlers;
- cleanup behavior after handler failure;
- exposed `availableActions()` policy.

That contract should live next to the adapter instead of being scattered across
`onStart()`, `onStop()`, and socket cleanup branches.

### Layer 3: Orion Lifecycle Adapter

An Orion adapter maps application lifecycle phases to service actions.

Example shape:

```java
LifecycleStateMachineAdapter<ServiceState, ServiceAction> adapter =
        LifecycleStateMachineAdapter.of(stateMachine);

adapter.register(
        registrar,
        ApplicationState.STARTING,
        OrionLifecycleTasks.TRANSPORT_LIFECYCLE_START,
        ServiceAction.START);

adapter.register(
        registrar,
        ApplicationState.STOPPING,
        OrionLifecycleTasks.TRANSPORT_LIFECYCLE_STOP,
        ServiceAction.STOP);
```

Only this adapter should know about `ApplicationState`,
`OrionLifecycleTasks`, and `OrionStageCallResult`.

## Native Git Transport Target Shape

`GitNativeTransportService` should become a coordinator for one or more
listener endpoints.

Target structure:

```text
GitNativeTransportService
  - NativeGitListenerEndpoint primary
  - NativeGitListenerEndpoint secondary
  - StateMachine<ServiceState, ServiceAction> serviceState
```

Each endpoint owns:

- address;
- port;
- backlog;
- socket timeout;
- server socket;
- accept loop;
- active connection cleanup.

The service-level machine coordinates aggregate actions:

- `START` starts all configured endpoints;
- `STOP` stops all configured endpoints;
- if any endpoint fails during start, already-started endpoints remain running
  until an explicit `STOP` cleans them up;
- `availableActions()` reflects the aggregate service state.

This allows running two `git://` listener ports while keeping lifecycle policy
centralized and inspectable.

## Phased Plan

### Phase 1: Generic State Machine Utility

Add a small utility package in `core/common`, for example:

```text
pro.deta.orion.lifecycle.state
```

Implement:

- state/action definition builder;
- immutable transition table;
- current state holder;
- `availableActions()`;
- `execute(action)`;
- invalid transition exception;
- listener callbacks.

Keep this package independent from application lifecycle classes except for
optional naming conventions.

### Phase 2: Unit Tests For The Utility

Cover:

- valid transition changes state;
- invalid action is rejected and state is unchanged;
- `availableActions()` changes after transition;
- failure transition moves to configured failure state;
- listener receives old state, action, new state;
- concurrent action execution is serialized;
- terminal states expose no actions unless explicitly configured.

### Phase 3: Orion Lifecycle Adapter

Add an adapter that converts application lifecycle tasks into state machine
actions.

Current status: a generic adapter is deferred. The transport aggregate now has a
narrow adapter inside `TransportLifecycleStateMachine`, which registers
transport-wide `TRANSPORT_LIFECYCLE_START` and `TRANSPORT_LIFECYCLE_STOP`
lifecycle task ids and executes aggregate `ActionId.START` / `ActionId.STOP`
transitions. Those aggregate actions propagate to the explicitly listed child
state machines.

Cover:

- `ApplicationState.STARTING` can execute `START`;
- `ApplicationState.STOPPING` can execute `STOP`;
- task ids and dependencies remain visible in `describeTaskPlan`;
- invalid machine transition makes the lifecycle task fail;
- successful lifecycle tasks can execute transitions without adding
  `OrionStageCallResult` to the state-machine model.

### Phase 4: Native Git Listener Endpoint

Extract socket-specific behavior from `GitNativeTransportService` into
`NativeGitListenerEndpoint`.

Current status: do not introduce a new class only for naming. In the current
runtime shape, `GitNativeTransportService` is already the socket endpoint
implementation. Extract `NativeGitListenerEndpoint` later only if the native Git
transport needs more than one listener or selectable listener engines.

The endpoint should be testable without the application lifecycle and should
support port `0` for tests. It should expose the bound address after start.

Move existing native transport baseline tests to the endpoint where appropriate,
then keep service-level aggregate tests around the coordinator.

### Phase 5: Migrate `GitNativeTransportService`

Refactor `GitNativeTransportService` to:

- build endpoint definitions from configuration;
- own one service state machine;
- register lifecycle actions through the Orion adapter;
- start and stop endpoints through state-machine actions;
- expose service status for future admin surfaces.

### Phase 6: Multi-Endpoint Tests

Add tests proving:

- two endpoints can listen on two different dynamic ports;
- service `START` starts both endpoints;
- service `STOP` stops both endpoints;
- one endpoint bind failure leaves any already-started endpoint running, marks
  the aggregate failed, and permits cleanup through `STOP`;
- invalid repeated `START` is rejected or is explicitly idempotent;
- `availableActions()` is correct in `NEW`, `RUNNING`, `STOPPING`,
  `STOPPED`, and `FAILED`.

## Test Matrix

Generic state machine tests:

- transition table validation;
- valid/invalid actions;
- listener notification;
- failure path;
- concurrency.

Lifecycle adapter tests:

- registration into `ApplicationStateListenerRegistrar`;
- task id and dependency preservation;
- success/failure conversion into `OrionStageCallResult`.

Native Git endpoint tests:

- bind to loopback port `0`;
- dispatch accepted local connections;
- idle timeout;
- shutdown closes listener;
- malformed input does not stop listener.

Native Git service tests:

- one endpoint;
- two endpoints;
- partial start failure without automatic rollback;
- aggregate state and available actions.

## Resolved Decisions

Endpoint startup failure uses partial-running semantics: successful endpoints
stay `RUNNING`, failed endpoints move to their failure state, and the aggregate
moves to `FAILED`.

`FAILED` allows `STOP` when cleanup is still required after partial startup.
Cleanup happens through that explicit transition, not before moving to
`FAILED`.

## Open Questions

Should repeated `START` in `RUNNING` be rejected or treated as idempotent?

Should repeated `STOP` in `STOPPED` be rejected or treated as idempotent?

Should endpoint state be visible as child state machines, or as resource status
events attached to one parent state machine?

Should this utility support asynchronous transitions directly, or should async
work remain in adapters through existing `OrionStageCallResult` futures?

## Verification

The plan is complete when:

- generic state machine tests pass without application lifecycle dependencies;
- Orion lifecycle adapter tests prove no lifecycle task hardcoding is required
  in services;
- `GitNativeTransportService` can manage at least two listener endpoints through
  one centralized service state machine;
- existing native Git transport baseline tests still pass;
- routine `mvn test -Pdev` passes.

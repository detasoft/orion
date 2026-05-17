# Lifecycle State Machine

`core/lifecycle-state-machine` contains a small lifecycle state machine utility
for service-local state. It is intentionally independent from Orion application
lifecycle classes, sockets, Git transports, storage, ACLs, and dependency
injection.

Use it when a service or a service component needs an explicit lifecycle
contract:

- fixed states;
- typed actions and payloads;
- legal transitions;
- failure states;
- available actions from the current state;
- observable transition events;
- layered high-level actions such as `START` and `STOP`.

## Core Concepts

`StateMachineDefinition` is the lifecycle contract. It declares transitions
before a service is wired into the runtime:

```java
ActionBinding<StartTransport> start = ActionBinding.of(
        "git.transport.start",
        action -> endpoint.start(action.endpoints()));
ActionBinding<StopTransport> stop = ActionBinding.of(
        "git.transport.stop",
        action -> endpoint.stop());

State running = StateMachineDefinition.state("RUNNING");

StateMachine machine = StateMachineDefinition.define()
        .from(StateMachineDefinition.NEW)
        .on(start)
        .to(running)
        .failTo(StateMachineDefinition.ERR)

        .from(running)
        .on(stop)
        .to(StateMachineDefinition.FIN)
        .failTo(StateMachineDefinition.ERR)
        .build()
        .newStateMachine();
```

`NEW` and `FIN` are fixed definitions for every machine:

- `NEW` is the initial state;
- `FIN` is terminal by default;
- `ERR` is the conventional failure state, but it is not special unless the
  definition uses it.

`ActionId` is the stable semantic identity of an action. Different machines may
share the same id, and one machine may expose the same id from several different
states. This lets a single external action such as `START` progress through
several lifecycle layers.

`ActionBinding<A>` combines an `ActionId` with a typed handler. The binding
instance is the precise key for low-level single-step execution. The binding id
is the key for high-level action execution.

For one source state there must be at most one transition with a given
`ActionId`. This keeps `execute(actionId, payload)` deterministic.

Every transition must define `failTo(...)`. If the handler throws, the machine
moves to that failure state and throws `StateTransitionFailedException` to the
caller.

Invalid actions are rejected before any handler runs. They do not emit
transition events and do not change state.

## Execution Model

`StateMachine.execute(action, payload)` performs exactly one synchronous
transition selected by the concrete `ActionBinding`:

1. Validate that the current state allows the supplied binding.
2. Emit `TRANSITION_STARTED`.
3. Emit `TRANSITION_FUNCTION_STARTED`.
4. Run the action handler.
5. Emit `TRANSITION_FUNCTION_FINISHED`.
6. Move to the success target or the configured failure state.
7. Emit `AFTER_STATE_ENTERED`.
8. Notify transition listeners.
9. Emit `TRANSITION_FINISHED` or `TRANSITION_FAILED`.

`StateMachine.execute(actionId, payload)` is the default high-level mode. It
executes a sequence of synchronous transitions:

1. Select the binding with `actionId` available from the current state.
2. Execute that concrete transition.
3. If the new state also exposes `actionId`, continue with that state's binding.
4. Stop when the machine reaches a state that does not expose `actionId`.

If the first state does not expose `actionId`, the call is rejected as an
invalid transition. If any layer fails, the current transition moves to its
configured `failTo(...)` state and the call throws.

Transitions are serialized by the machine instance. A handler should therefore
avoid waiting for another action on the same machine.

## Layered Actions

Different bindings may share the same `ActionId` when they represent the same
external command from different states:

```java
ActionBinding<Void> startInit = ActionBinding.of(ActionId.START, adapter::startInit);
ActionBinding<Void> startAcl = ActionBinding.of(ActionId.START, adapter::startAcl);
ActionBinding<Void> startTransports = ActionBinding.of(ActionId.START, adapter::startTransports);

StateMachine machine = StateMachineDefinition.define()
        .from(NEW)
        .on(startInit)
        .to(INIT_READY)
        .failTo(ERR)

        .from(INIT_READY)
        .on(startAcl)
        .to(ACL_READY)
        .failTo(ERR)

        .from(ACL_READY)
        .on(startTransports)
        .to(RUNNING)
        .failTo(ERR)
        .build()
        .newStateMachine();

machine.execute(ActionId.START, Void.EMPTY);
```

The call above runs `startInit`, then `startAcl`, then `startTransports`, because
the same action id is available after each successful layer. Calling
`machine.execute(startInit, Void.EMPTY)` would run only the first concrete
transition.

Adapters may use their own executor or thread pool inside a layer when several
child services should start in parallel. The state machine itself remains
synchronous: the action handler decides what work to perform and returns only
when that layer is complete.

## Observability

Subscribers and listeners are observers. Their exceptions are logged and ignored
so they cannot change machine behavior.

Use observers for different levels of detail:

- `StateMachineEventSubscriber` receives internal transition points such as
  `TRANSITION_STARTED`, `TRANSITION_FUNCTION_STARTED`, and
  `AFTER_STATE_ENTERED`. It is useful for logging, timing, diagnostics, and
  derived monitoring.
- `StateMachineListener` receives one `StateTransitionEvent` after the machine
  has entered the new state. It is useful when only completed transitions matter.
- Service adapters are not observers. They define and execute actions for a
  concrete service.

`describe()` returns the current state, an in-progress transition if there is
one, and the configured transition diagram.

## Service Adapters

A concrete service should not own lifecycle policy directly. Put the lifecycle
definition and action bindings into an adapter that owns the connection between:

- the concrete service instance;
- the service-local `StateMachine`;
- action bindings and payload types;
- service callbacks for runtime failures or other service-originated events.

The service should know only a small domain callback when it needs to report
runtime state changes:

```java
interface TransportEvents {
    void failed(Throwable failure);
}
```

The adapter implements that callback and translates it to an explicit machine
action:

```java
record TransportFailure(Throwable cause) {
}

ActionBinding<TransportFailure> failed = ActionBinding.of(
        "git.transport.failed",
        failure -> service.recordFailure(failure.cause()));

StateMachine machine = StateMachineDefinition.define()
        .from(NEW)
        .on(start)
        .to(RUNNING)
        .failTo(ERR)

        .from(RUNNING)
        .on(failed)
        .to(ERR)
        .failTo(ERR)
        .build()
        .newStateMachine();

public void failed(Throwable failure) {
    try {
        machine.execute(failed, new TransportFailure(failure));
    } catch (InvalidStateTransitionException ignoredLateFailure) {
        // The adapter may choose to ignore or log duplicate late failures.
    }
}
```

This keeps the boundaries clear:

- the service owns concrete work;
- the adapter owns lifecycle integration and typed payload construction;
- the state machine owns legal transitions;
- observers see state changes after they happen.

If an action handler fails during `execute(...)`, `failTo(...)` handles it. If a
running service later detects a failure by itself, model that as another
explicit action such as `failed`, `disconnected`, or `stopped`.

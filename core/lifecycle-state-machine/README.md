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
- default and custom state resolution after an action;
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

State running = StandardStateDefinition.state("RUNNING");

StateMachine machine = StateMachineDefinition.define()
        .from(StandardStateDefinition.NEW)
        .on(start)
        .to(running, StandardStateDefinition.ERR)

        .from(running)
        .on(stop)
        .to(StandardStateDefinition.FIN, StandardStateDefinition.ERR)
        .build()
        .newStateMachine();
```

`NEW` and `FIN` are fixed definitions for every machine:

- `NEW` is the initial state;
- `FIN` is terminal by default;
- `ERR` is the conventional failure state used by the default resolver when an
  action throws and `ERR` is one of the transition targets.

`ActionId` is the stable semantic identity of an action. Different machines may
share the same id, and one machine may expose the same id from several different
states. This lets a single external action such as `START` progress through
several lifecycle layers.

`ActionBinding<A>` combines an `ActionId` with a typed handler. `A` is the
payload type. The handler may still return a value, and that value is stored as
`Object actionResult` in `StateTransitionResult` for diagnostics. The binding
instance is the precise key for low-level single-step execution. The binding id
is the key for high-level action execution.

For one source state there must be at most one transition with a given
`ActionId`. This keeps `execute(actionId, payload)` deterministic.

When several states share the same action, list them in one `from(...)` rule:

```java
StateMachineDefinition.define()
        .from(NEW, RUNNING)
        .on(stop)
        .to(FIN, ERR);
```

`to(...)` lists all allowed target states. If no explicit `post(...)` resolver
is configured, the default resolver is used:

- if the handler throws, select `ERR` when it is one of the targets;
- if the handler succeeds, ignore `ERR` and select the only remaining target;
- if several non-`ERR` success targets remain, throw `IllegalStateException`
  and require an explicit `post(...)` resolver.

Custom resolution receives a `StateTransitionResult` containing the source
state, action id, payload, allowed targets, diagnostic action result, failure,
and selected state when already resolved:

```java
final class TransportAdapter {
    private final TransportService service;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startTransport);

    private Void startTransport(Void ignored) {
        service.onStart();
        return Void.EMPTY;
    }

    private State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        return service.isEnabled() ? RUNNING : DISABLED;
    }

    StateMachine createStateMachine() {
        return StateMachineDefinition.define()
                .from(NEW, DISABLED)
                .on(start)
                .to(DISABLED, RUNNING, ERR)
                .post(this::resolveStartState)
                .build()
                .newStateMachine();
    }
}
```

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
6. Resolve and move to one of the configured target states.
7. Emit `AFTER_STATE_ENTERED`.
8. Emit `TRANSITION_FINISHED` or `TRANSITION_FAILED`.

`StateMachine.execute(actionId, payload)` is the default high-level mode. It
executes a sequence of synchronous transitions:

1. Select the binding with `actionId` available from the current state.
2. Execute that concrete transition.
3. If the new state also exposes `actionId`, continue with that state's binding.
4. Stop when the machine reaches a state that does not expose `actionId`.

If the first state does not expose `actionId`, the call is rejected as an
invalid transition. If any layer fails, the current transition resolves to
`ERR` when configured and the call throws. If the next state exposes the same
`ActionBinding` instance again, the high-level action stops instead of repeating
the same binding indefinitely.

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
        .to(INIT_READY, ERR)

        .from(INIT_READY)
        .on(startAcl)
        .to(ACL_READY, ERR)

        .from(ACL_READY)
        .on(startTransports)
        .to(RUNNING, ERR)
        .build()
        .newStateMachine();

machine.execute(ActionId.START, Void.EMPTY);
```

The call above runs `startInit`, then `startAcl`, then `startTransports`, because
the same action id is available after each successful layer. Calling
`machine.execute(startInit, Void.EMPTY)` would run only the first concrete
transition.

Adapters may use their own executor or thread pool inside a normal handler when
they need custom parallel work. Aggregate machines can define transitions
directly on an `ActionId`; then the machine uses its configured child
propagation mode when the action is executed.

## Child Machines

A machine may register child machines in its definition. A transition declared
with `.on(ActionId.START)` has no local action binding. When it is executed, the
machine looks at registered children and executes the same `ActionId` on children
that currently expose it.

```java
StateMachine parent = StateMachineDefinition.define()
        .childPropagationMode(StateMachineDefinition.ChildPropagationMode.PARALLEL)
        .child("storage", storageMachine)
        .child("acl", aclMachine)
        .childExecutor(executor)
        .from(NEW)
        .on(ActionId.START)
        .to(RUNNING, ERR)
        .build()
        .newStateMachine();
```

For aggregates with standard start/stop entry points, `AggregateStateMachine`
wraps the underlying machine and exposes aggregate lifecycle operations such as
`start()`, `stop()`, `execute(...)`, `status()`, `childStatuses()`, and
`describeStatus()` without adding adapter action methods:

```java
AggregateStateMachine aggregate = new AggregateStateMachine(definition);
aggregate.start();
```

Future API direction: `AggregateStateMachine` is intended to be the facade for
aggregate lifecycle use cases, including child composition and propagation. The
current implementation still stores child machines and propagation settings in
`StateMachineDefinition`, and the underlying `StateMachine` performs the actual
child action propagation. This keeps the current builder DSL small, but callers
that deal with aggregate lifecycle status should prefer aggregate-level APIs
over exposing the raw underlying `StateMachine`. If aggregate behavior grows,
split the aggregate definition/builder from the plain state machine definition
instead of collapsing child and propagation concerns into unrelated service
state machines.

The parent reads direct child status from the child machines:

```java
parent.childStatuses().get("storage").state(); // NEW
```

`ChildPropagationMode.SEQUENTIAL` runs children in registration order.
`ChildPropagationMode.PARALLEL` requires `childExecutor(...)`.

The parent waits for every selected child to finish. If any child fails, the
parent transition fails and resolves through the parent's configured targets.

Child transitions do not start follow-up actions by themselves. Follow-up layers
are still driven by the parent's own `execute(actionId, payload)` cascade.

## Computed State

Every machine has a physical state: `currentState()`. A parent may also expose a
computed state derived from its physical state and the current states of its
direct children:

```java
StateMachine parent = StateMachineDefinition.define()
        .child("storage", storageMachine)
        .child("acl", aclMachine)
        .computedState((physicalState, childStates) -> {
            if (childStates.values().contains(ERR)) {
                return ERR;
            }
            if (childStates.get("storage") == RUNNING && childStates.get("acl") == RUNNING) {
                return RUNTIME_READY;
            }
            return physicalState;
        })
        .build()
        .newStateMachine();
```

`computedState()` is for monitoring and diagnostics and is calculated from the
machine's current state plus the current states of its direct children. It does
not replace the physical state used to validate transitions. This keeps command
availability deterministic while still giving the parent a place to publish
aggregate status.

`childStatuses()` exposes direct children as structured status nodes, so every
child entry has both `state` and `computedState`. `status()` returns the same
distinction for the current machine and its recursive child tree.
`describeStatus()` is the human-readable rendering of that structured status.

## Observability

Subscribers are observers. Their exceptions are logged and ignored so they
cannot change machine behavior.

`StateMachineEventSubscriber` receives internal transition points such as
`TRANSITION_STARTED`, `TRANSITION_FUNCTION_STARTED`, `AFTER_STATE_ENTERED`, and
`TRANSITION_FINISHED`. It is useful for logging, timing, diagnostics, and
derived monitoring. Service adapters are not observers. They define and execute
actions for a concrete service.

`describe()` returns the current state, an in-progress transition if there is
one, the last transition result, the configured transition diagram, and nested
descriptions of child machines when the machine has registered children.

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

ActionBinding<TransportFailure> failed =
        ActionBinding.of("git.transport.failed", this::recordFailure);

StateMachine machine = StateMachineDefinition.define()
        .from(NEW)
        .on(start)
        .to(RUNNING, ERR)

        .from(RUNNING)
        .on(failed)
        .to(ERR)
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

If an action handler fails during `execute(...)`, the transition's resolver
handles it. If a running service later detects a failure by itself, model that
as another explicit action such as `failed`, `disconnected`, or `stopped`.

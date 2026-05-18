package pro.deta.orion.lifecycle.state;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Runtime instance of a {@link StateMachineDefinition}.
 *
 * <p>Executing by {@link ActionBinding} performs exactly one concrete transition. Executing by {@link ActionId} is the
 * default high-level mode: the machine selects the currently available binding with that id, executes it, and keeps
 * going while the same id remains available from the next state. A transition handler may explicitly propagate the
 * same action id to child machines in sequential or parallel mode. Transitions are serialized per machine instance, and
 * observer failures are logged without changing lifecycle behavior.</p>
 */
@Slf4j
public final class StateMachine implements AutoCloseable {
    private final StateMachineDefinition definition;
    private final List<StateMachineListener> listeners = new CopyOnWriteArrayList<>();
    private final List<StateMachineEventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Map<String, StateMachine> children;
    private final Map<String, StateMachineDefinition.State> childStates = new ConcurrentHashMap<>();
    private final List<StateMachineSubscription> childSubscriptions = new CopyOnWriteArrayList<>();
    private volatile StateMachineDefinition.State currentState;
    private volatile StateMachineDefinition.State computedState;
    private volatile StateTransition<?> transitionInProgress;

    public StateMachine(StateMachineDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        children = definition.children();
        currentState = definition.initialState();
        for (Map.Entry<String, StateMachine> child : children.entrySet()) {
            childStates.put(child.getKey(), child.getValue().currentState());
            childSubscriptions.add(child.getValue().subscribe(event -> onChildEvent(child.getKey(), event)));
        }
        computedState = resolveComputedState();
    }

    public static StateMachine create(StateMachineDefinition definition) {
        return new StateMachine(definition);
    }

    public synchronized StateMachineDefinition.State currentState() {
        return currentState;
    }

    public StateMachineDefinition.State computedState() {
        return computedState;
    }

    public Map<String, StateMachineDefinition.State> childStates() {
        Map<String, StateMachineDefinition.State> snapshot = new LinkedHashMap<>();
        for (String child : children.keySet()) {
            snapshot.put(child, childStates.get(child));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized Set<ActionId> availableActions() {
        return definition.availableActions(currentState);
    }

    public Set<StateMachineDefinition.State> states() {
        return definition.states();
    }

    public synchronized List<StateTransition<?>> availableTransitions() {
        return definition.transitionsFrom(currentState);
    }

    public synchronized StateMachineSnapshot snapshot() {
        return new StateMachineSnapshot(
                currentState,
                computedState,
                childStates(),
                definition.availableActions(currentState),
                definition.isTerminalState(currentState));
    }

    public String describe() {
        StringBuilder builder = new StringBuilder();
        describeInto(builder, "");
        return builder.toString();
    }

    private void describeInto(StringBuilder builder, String indent) {
        StateMachineDefinition.State state = currentState;
        StateTransition<?> activeTransition = transitionInProgress;
        builder.append(indent).append("state: ").append(state);
        builder.append("\n").append(indent).append("in progress: ");
        if (activeTransition == null) {
            builder.append("<none>");
        } else {
            builder.append(activeTransition.describe());
        }
        appendIndented(builder, indent, definition.transitionDiagram());
        if (children.isEmpty()) {
            return;
        }
        builder.append("\n").append(indent).append("children:");
        for (Map.Entry<String, StateMachine> child : children.entrySet()) {
            builder.append("\n").append(indent).append("  ").append(child.getKey()).append(":\n");
            child.getValue().describeInto(builder, indent + "    ");
        }
    }

    private static void appendIndented(StringBuilder builder, String indent, String value) {
        String[] lines = value.split("\n", -1);
        for (String line : lines) {
            builder.append("\n").append(indent).append(line);
        }
    }

    public void addListener(StateMachineListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public StateMachineSubscription subscribe(StateMachineEventSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public synchronized <A> StateTransitionEvent execute(ActionBinding<A> action, A payload) {
        Objects.requireNonNull(action, "action");
        StateTransition<A> transition = definition.transition(currentState, action)
                .orElseThrow(() -> new InvalidStateTransitionException(currentState, action.id()));
        return executeTransition(transition, payload);
    }

    public <A> List<StateTransitionEvent> execute(ActionId actionId, A payload) {
        Objects.requireNonNull(actionId, "actionId");
        return executeTransitionSequence(actionId, payload);
    }

    public <A> LifecycleActionHandler<A> propagateSequentialHandler(ActionId actionId) {
        Objects.requireNonNull(actionId, "actionId");
        return payload -> propagateSequential(actionId, payload);
    }

    public <A> LifecycleActionHandler<A> propagateParallelHandler(ActionId actionId) {
        Objects.requireNonNull(actionId, "actionId");
        return payload -> propagateParallel(actionId, payload);
    }

    public synchronized <A> List<StateTransitionEvent> propagateSequential(ActionId actionId, A payload) {
        Objects.requireNonNull(actionId, "actionId");
        List<ChildAction<A>> actions = availableChildActions(actionId, payload);
        if (actions.isEmpty()) {
            return List.of();
        }
        return executeChildActionsSequentially(actionId, actions);
    }

    public synchronized <A> List<StateTransitionEvent> propagateParallel(ActionId actionId, A payload) {
        Objects.requireNonNull(actionId, "actionId");
        List<ChildAction<A>> actions = availableChildActions(actionId, payload);
        if (actions.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = definition.childExecutor();
        if (executor == null) {
            throw new IllegalStateException("Parallel child action " + actionId + " requires childExecutor");
        }
        return executeChildActionsInParallel(actionId, actions, executor);
    }

    private synchronized <A> List<StateTransitionEvent> executeTransitionSequence(ActionId actionId, A payload) {
        List<StateTransitionEvent> events = new ArrayList<>();
        while (true) {
            StateTransition<?> transition = definition.transition(currentState, actionId)
                    .orElse(null);
            if (transition == null) {
                if (events.isEmpty()) {
                    throw new InvalidStateTransitionException(currentState, actionId);
                }
                return List.copyOf(events);
            }
            @SuppressWarnings("unchecked")
            StateTransition<A> typedTransition = (StateTransition<A>) transition;
            events.add(executeTransition(typedTransition, payload));
        }
    }

    private synchronized <A> StateTransitionEvent executeTransition(StateTransition<A> transition, A payload) {
        StateMachineDefinition.State oldState = currentState;
        transitionInProgress = transition;
        try {
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_STARTED,
                    oldState,
                    transition.actionId(),
                    payload,
                    transition.to(),
                    oldState,
                    null));
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FUNCTION_STARTED,
                    oldState,
                    transition.actionId(),
                    payload,
                    transition.to(),
                    currentState,
                    null));
            Exception failure = null;
            try {
                executeTransitionAction(transition, payload);
            } catch (Exception e) {
                failure = e;
            }

            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FUNCTION_FINISHED,
                    oldState,
                    transition.actionId(),
                    payload,
                    transition.to(),
                    currentState,
                    failure));
            StateMachineDefinition.State nextState;
            if (failure == null) {
                nextState = transition.to();
            } else {
                nextState = transition.failureState();
            }
            currentState = nextState;
            computedState = resolveComputedState();
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.AFTER_STATE_ENTERED,
                    oldState,
                    transition.actionId(),
                    payload,
                    transition.to(),
                    currentState,
                    failure));
            StateTransitionEvent event = new StateTransitionEvent(
                    oldState,
                    transition.actionId(),
                    payload,
                    currentState,
                    failure);
            notifyListeners(event);
            if (failure == null) {
                notifySubscribers(new StateMachineEvent(
                        StateMachineEventType.TRANSITION_FINISHED,
                        oldState,
                        transition.actionId(),
                        payload,
                        transition.to(),
                        currentState,
                        null));
                return event;
            }
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FAILED,
                    oldState,
                    transition.actionId(),
                    payload,
                    transition.to(),
                    currentState,
                    failure));
            throw new StateTransitionFailedException(oldState, transition.actionId(), transition.to(), currentState, failure);
        } finally {
            transitionInProgress = null;
        }
    }

    private <A> void executeTransitionAction(StateTransition<A> transition, A payload) throws Exception {
        transition.execute(payload);
    }

    private <A> List<ChildAction<A>> availableChildActions(ActionId actionId, A payload) {
        List<ChildAction<A>> actions = new ArrayList<>();
        for (Map.Entry<String, StateMachine> child : children.entrySet()) {
            if (child.getValue().definition.transition(child.getValue().currentState(), actionId).isPresent()) {
                actions.add(new ChildAction<>(child.getKey(), child.getValue(), actionId, payload));
            }
        }
        return actions;
    }

    private <A> List<StateTransitionEvent> executeChildActionsSequentially(
            ActionId actionId,
            List<ChildAction<A>> actions) {
        List<StateTransitionEvent> events = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (ChildAction<A> action : actions) {
            try {
                events.addAll(action.execute());
            } catch (Throwable e) {
                failures.add(e);
            }
        }
        throwFailures("Child action " + actionId + " failed", failures);
        return List.copyOf(events);
    }

    private <A> List<StateTransitionEvent> executeChildActionsInParallel(
            ActionId actionId,
            List<ChildAction<A>> actions,
            ExecutorService executor) {
        List<Callable<List<StateTransitionEvent>>> tasks = new ArrayList<>();
        for (ChildAction<A> action : actions) {
            tasks.add(action::execute);
        }
        return executeActionsInParallel("Child action " + actionId + " failed", tasks, executor);
    }

    private static List<StateTransitionEvent> executeActionsInParallel(
            String failureMessage,
            List<Callable<List<StateTransitionEvent>>> tasks,
            ExecutorService executor) {
        List<StateTransitionEvent> events = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<List<StateTransitionEvent>>> futures = executor.invokeAll(tasks);
            for (Future<List<StateTransitionEvent>> future : futures) {
                try {
                    events.addAll(future.get());
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing " + failureMessage, e);
        }

        throwFailures(failureMessage, failures);
        return List.copyOf(events);
    }

    private static void throwFailures(String message, List<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }
        IllegalStateException failure = new IllegalStateException(message, failures.getFirst());
        for (int i = 1; i < failures.size(); i++) {
            failure.addSuppressed(failures.get(i));
        }
        throw failure;
    }

    private void onChildEvent(String child, StateMachineEvent event) {
        if (event.type() != StateMachineEventType.AFTER_STATE_ENTERED) {
            return;
        }
        childStates.put(child, event.currentState());
        computedState = resolveComputedState();
    }

    private StateMachineDefinition.State resolveComputedState() {
        return definition.computedStateResolver().resolve(currentState, childStates());
    }

    private void notifyListeners(StateTransitionEvent event) {
        for (StateMachineListener listener : listeners) {
            try {
                listener.onTransition(event);
            } catch (RuntimeException e) {
                log.warn("State machine listener failed while observing transition {}", event, e);
            }
        }
    }

    private void notifySubscribers(StateMachineEvent event) {
        for (StateMachineEventSubscriber subscriber : subscribers) {
            try {
                subscriber.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("State machine subscriber failed while observing event {}", event, e);
            }
        }
    }

    @Override
    public void close() {
        for (StateMachineSubscription subscription : childSubscriptions) {
            subscription.close();
        }
        childSubscriptions.clear();
    }

    private record ChildAction<A>(
            String name,
            StateMachine machine,
            ActionId actionId,
            A payload) {
        private ChildAction {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(machine, "machine");
            Objects.requireNonNull(actionId, "actionId");
        }

        private List<StateTransitionEvent> execute() {
            return machine.execute(actionId, payload);
        }
    }
}

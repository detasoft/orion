package pro.deta.orion.lifecycle.state;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Runtime instance of a {@link StateMachineDefinition}.
 *
 * <p>Executing by {@link ActionBinding} performs exactly one concrete transition. Executing by {@link ActionId} is the
 * default high-level mode: the machine selects the currently available binding with that id, executes it, and keeps
 * going while the same id remains available from the next state. A transition defined directly on an {@link ActionId}
 * has no local handler; the machine uses its child propagation mode to execute the same action on child machines.
 * Transitions are serialized per machine instance, and observer failures are logged without changing lifecycle
 * behavior.</p>
 */
@Slf4j
public final class StateMachine {
    private final StateMachineDefinition definition;
    private final List<StateMachineEventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final Map<String, StateMachine> children;
    private volatile StateMachineDefinition.State currentState;
    private volatile StateTransition transitionInProgress;
    private volatile StateTransitionResult lastTransitionResult;

    public StateMachine(StateMachineDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        children = definition.children();
        currentState = definition.initialState();
        for (StateTransition transition : definition.transitions()) {
            transition.register(this);
        }
    }

    public static StateMachine create(StateMachineDefinition definition) {
        return new StateMachine(definition);
    }

    public synchronized StateMachineDefinition.State currentState() {
        return currentState;
    }

    public String name() {
        return definition.name();
    }

    public StateMachineDefinition.State computedState() {
        return resolveComputedState();
    }

    public Map<String, StateMachineStatus> childStatuses() {
        Map<String, StateMachineStatus> statuses = new LinkedHashMap<>();
        for (Map.Entry<String, StateMachine> child : children.entrySet()) {
            statuses.put(child.getKey(), child.getValue().status(child.getKey()));
        }
        return Collections.unmodifiableMap(statuses);
    }

    public synchronized Set<ActionId> availableActions() {
        return definition.availableActions(currentState);
    }

    public Set<StateMachineDefinition.State> states() {
        return definition.states();
    }

    public synchronized List<StateTransition> availableTransitions() {
        return definition.transitionsFrom(currentState);
    }

    public StateMachineStatus status() {
        return status(name());
    }

    private synchronized StateMachineStatus status(String name) {
        return new StateMachineStatus(
                requireName(name),
                currentState,
                computedState(),
                childStatuses(),
                definition.availableActions(currentState),
                definition.isTerminalState(currentState));
    }

    public StateTransitionResult lastTransitionResult() {
        return lastTransitionResult;
    }

    public String describe() {
        StringBuilder builder = new StringBuilder();
        describeInto(builder, "");
        return builder.toString();
    }

    public String describeStatus() {
        StringBuilder builder = new StringBuilder();
        describeStatusInto(builder, "", status());
        return builder.toString();
    }

    private void describeStatusInto(StringBuilder builder, String indent, StateMachineStatus status) {
        StateMachineDefinition.State state = status.state();
        StateMachineDefinition.State computedState = status.computedState();
        builder.append(indent).append(status.name()).append(": ").append(computedState);
        if (!computedState.equals(state)) {
            builder.append(" (state=").append(state).append(")");
        }
        StateTransitionResult lastResult = lastTransitionResult;
        if (StandardStateDefinition.ERR.equals(state) && lastResult != null && lastResult.failure() != null) {
            builder.append(System.lineSeparator())
                    .append(indent)
                    .append("  last transition: ");
            appendTransitionResult(builder, lastResult);
            builder.append(System.lineSeparator())
                    .append(indent)
                    .append("  error: ")
                    .append(lastResult.failure());
        }
        for (Map.Entry<String, StateMachine> child : children.entrySet()) {
            builder.append(System.lineSeparator());
            StateMachineStatus childStatus = Objects.requireNonNull(
                    status.children().get(child.getKey()),
                    "child status: " + child.getKey());
            child.getValue().describeStatusInto(builder, indent + "  ", childStatus);
        }
    }

    private void describeInto(StringBuilder builder, String indent) {
        StateMachineDefinition.State state = currentState;
        StateTransition activeTransition = transitionInProgress;
        builder.append(indent).append("state: ").append(state);
        builder.append("\n").append(indent).append("in progress: ");
        if (activeTransition == null) {
            builder.append("<none>");
        } else {
            builder.append(activeTransition.describe());
        }
        StateTransitionResult lastResult = lastTransitionResult;
        builder.append("\n").append(indent).append("last transition: ");
        if (lastResult == null) {
            builder.append("<none>");
        } else {
            appendTransitionResult(builder, lastResult);
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

    private static void appendTransitionResult(StringBuilder builder, StateTransitionResult result) {
        builder.append(result.from())
                .append(" --")
                .append(result.action())
                .append("--> ");
        if (result.to() == null) {
            builder.append("<unresolved>");
        } else {
            builder.append(result.to());
        }
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return value;
    }

    public StateMachineSubscription subscribe(StateMachineEventSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public synchronized <A> StateTransitionResult execute(ActionBinding<A> action, A payload) {
        Objects.requireNonNull(action, "action");
        StateTransition transition = definition.transition(currentState, action)
                .orElseThrow(() -> new InvalidStateTransitionException(currentState, action.id()));
        return executeTransition(transition, action, payload);
    }

    public <A> List<StateTransitionResult> execute(ActionId actionId, A payload) {
        Objects.requireNonNull(actionId, "actionId");
        return executeTransitionSequence(actionId, payload);
    }

    private synchronized <A> List<StateTransitionResult> executeTransitionSequence(ActionId actionId, A payload) {
        List<StateTransitionResult> events = new ArrayList<>();
        Set<Object> executedTransitions = new HashSet<>();
        while (true) {
            StateTransition transition = definition.transition(currentState, actionId)
                    .orElse(null);
            if (transition == null) {
                if (events.isEmpty()) {
                    throw new InvalidStateTransitionException(currentState, actionId);
                }
                return List.copyOf(events);
            }
            Object executionKey = transition.action() == null ? transition : transition.action();
            if (!executedTransitions.add(executionKey)) {
                return List.copyOf(events);
            }
            events.add(executeTransition(transition, payload));
        }
    }

    private synchronized <A> List<StateTransitionResult> executeSingle(ActionId actionId, A payload) {
        StateTransition transition = definition.transition(currentState, actionId)
                .orElseThrow(() -> new InvalidStateTransitionException(currentState, actionId));
        return List.of(executeTransition(transition, payload));
    }

    private synchronized <A> StateTransitionResult executeTransition(StateTransition transition, A payload) {
        ActionBinding<?> action = transition.action();
        if (action == null) {
            return executeTransition(transition, null, payload);
        }
        return executeTransition(transition, actionBinding(transition), payload);
    }

    private synchronized <A> StateTransitionResult executeTransition(
            StateTransition transition,
            ActionBinding<A> action,
            A payload) {
        StateMachineDefinition.State oldState = currentState;
        transitionInProgress = transition;
        StateMachineDefinition.State eventTarget = transition.targets().getFirst();
        try {
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_STARTED,
                    oldState,
                    transition.actionId(),
                    payload,
                    eventTarget,
                    oldState,
                    null));
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FUNCTION_STARTED,
                    oldState,
                    transition.actionId(),
                    payload,
                    eventTarget,
                    currentState,
                    null));
            Object actionResult = null;
            Exception failure = null;
            try {
                actionResult = executeTransitionAction(transition, action, payload);
            } catch (Exception e) {
                failure = e;
            }

            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FUNCTION_FINISHED,
                    oldState,
                    transition.actionId(),
                    payload,
                    eventTarget,
                    currentState,
                    failure));
            StateTransitionResult unresolvedResult = new StateTransitionResult(
                    oldState,
                    transition.actionId(),
                    payload,
                    transition.targets(),
                    actionResult,
                    failure,
                    null);
            lastTransitionResult = unresolvedResult;
            StateMachineDefinition.State nextState = transition.resolver().resolve(unresolvedResult);
            if (!transition.targets().contains(nextState)) {
                throw new IllegalStateException(
                        "Transition " + transition.actionId() + " resolved to " + nextState
                                + " but allowed targets are " + transition.targets());
            }
            StateTransitionResult result = unresolvedResult.withTo(nextState);
            currentState = nextState;
            lastTransitionResult = result;
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.AFTER_STATE_ENTERED,
                    oldState,
                    transition.actionId(),
                    payload,
                    nextState,
                    currentState,
                    failure));
            if (failure == null) {
                notifySubscribers(new StateMachineEvent(
                        StateMachineEventType.TRANSITION_FINISHED,
                        oldState,
                        transition.actionId(),
                        payload,
                        nextState,
                        currentState,
                        null));
                return result;
            }
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FAILED,
                    oldState,
                    transition.actionId(),
                    payload,
                    nextState,
                    currentState,
                    failure));
            throw new StateTransitionFailedException(result);
        } finally {
            transitionInProgress = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <A> ActionBinding<A> actionBinding(StateTransition transition) {
        return (ActionBinding<A>) transition.action();
    }

    private <A> Object executeTransitionAction(
            StateTransition transition,
            ActionBinding<A> action,
            A payload) throws Exception {
        if (action != null) {
            return action.execute(payload);
        }
        return propagateChildActions(transition.actionId(), payload);
    }

    private <A> List<StateTransitionResult> propagateChildActions(ActionId actionId, A payload) {
        List<ChildAction<A>> actions = availableChildActions(actionId, payload);
        if (actions.isEmpty()) {
            return List.of();
        }
        return switch (definition.childPropagationMode()) {
            case NONE -> List.of();
            case SEQUENTIAL -> executeChildActionsSequentially(actionId, actions);
            case PARALLEL -> {
                ExecutorService executor = definition.childExecutor();
                if (executor == null) {
                    throw new IllegalStateException("Parallel child action " + actionId + " requires childExecutor");
                }
                yield executeChildActionsInParallel(actionId, actions, executor);
            }
        };
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

    private <A> List<StateTransitionResult> executeChildActionsSequentially(
            ActionId actionId,
            List<ChildAction<A>> actions) {
        List<StateTransitionResult> events = new ArrayList<>();
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

    private <A> List<StateTransitionResult> executeChildActionsInParallel(
            ActionId actionId,
            List<ChildAction<A>> actions,
            ExecutorService executor) {
        List<Callable<List<StateTransitionResult>>> tasks = new ArrayList<>();
        for (ChildAction<A> action : actions) {
            tasks.add(action::execute);
        }
        return executeActionsInParallel("Child action " + actionId + " failed", tasks, executor);
    }

    private static List<StateTransitionResult> executeActionsInParallel(
            String failureMessage,
            List<Callable<List<StateTransitionResult>>> tasks,
            ExecutorService executor) {
        List<StateTransitionResult> events = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<List<StateTransitionResult>>> futures = executor.invokeAll(tasks);
            for (Future<List<StateTransitionResult>> future : futures) {
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

    private StateMachineDefinition.State resolveComputedState() {
        return definition.computedStateResolver().resolve(currentState, currentChildStates());
    }

    private Map<String, StateMachineDefinition.State> currentChildStates() {
        Map<String, StateMachineDefinition.State> states = new LinkedHashMap<>();
        for (Map.Entry<String, StateMachine> child : children.entrySet()) {
            states.put(child.getKey(), child.getValue().currentState());
        }
        return Collections.unmodifiableMap(states);
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

        private List<StateTransitionResult> execute() {
            return machine.executeSingle(actionId, payload);
        }
    }
}

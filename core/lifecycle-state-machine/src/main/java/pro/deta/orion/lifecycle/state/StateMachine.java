package pro.deta.orion.lifecycle.state;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class StateMachine {
    private final StateMachineDefinition definition;
    private final List<StateMachineListener> listeners = new CopyOnWriteArrayList<>();
    private final List<StateMachineEventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile StateMachineDefinition.State currentState;
    private volatile StateTransition<?> transitionInProgress;

    public StateMachine(StateMachineDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        currentState = definition.initialState();
    }

    public static StateMachine create(StateMachineDefinition definition) {
        return new StateMachine(definition);
    }

    public synchronized StateMachineDefinition.State currentState() {
        return currentState;
    }

    public synchronized Set<ActionBinding<?>> availableActions() {
        return definition.availableActions(currentState);
    }

    public synchronized List<StateTransition<?>> availableTransitions() {
        return definition.transitionsFrom(currentState);
    }

    public synchronized List<StateTransition<?>> transitionsFrom(StateMachineDefinition.State state) {
        return definition.transitionsFrom(state);
    }

    public synchronized StateMachineSnapshot snapshot() {
        return new StateMachineSnapshot(
                currentState,
                definition.availableActions(currentState),
                definition.isTerminalState(currentState));
    }

    public String describe() {
        StateMachineDefinition.State state = currentState;
        StateTransition<?> activeTransition = transitionInProgress;
        StringBuilder builder = new StringBuilder();
        builder.append("state: ").append(state);
        builder.append("\nin progress: ");
        if (activeTransition == null) {
            builder.append("<none>");
        } else {
            builder.append(activeTransition.describe());
        }
        builder.append("\n").append(definition.transitionDiagram());
        return builder.toString();
    }

    public void addListener(StateMachineListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public StateMachineSubscription subscribe(StateMachineEventSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public <A> StateMachineEvent execute(ActionBinding<A> action, A payload) {
        Objects.requireNonNull(action, "action");
        return executeTransition(action, payload);
    }

    public <A> StateMachineEvent execute(StateTransition<A> transition, A payload) {
        Objects.requireNonNull(transition, "transition");
        return executeTransition(transition.action(), payload);
    }

    private synchronized <A> StateMachineEvent executeTransition(ActionBinding<A> action, A payload) {
        StateTransition<A> transition = definition.transition(currentState, action)
                .orElseThrow(() -> new InvalidStateTransitionException(currentState, action.id()));
        StateMachineDefinition.State oldState = currentState;
        Instant transitionStartedAt = Instant.now();
        transitionInProgress = transition;
        try {
            notifySubscribers(new StateMachineEventPoint(
                    StateMachineEventPointType.TRANSITION_STARTED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    oldState,
                    null,
                    transitionStartedAt));
            Instant functionStartedAt = Instant.now();
            notifySubscribers(new StateMachineEventPoint(
                    StateMachineEventPointType.TRANSITION_FUNCTION_STARTED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    currentState,
                    null,
                    functionStartedAt));
            Exception failure = null;
            Instant functionFinishedAt;
            try {
                transition.execute(payload);
            } catch (Exception e) {
                failure = e;
            } finally {
                functionFinishedAt = Instant.now();
            }

            notifySubscribers(new StateMachineEventPoint(
                    StateMachineEventPointType.TRANSITION_FUNCTION_FINISHED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    currentState,
                    failure,
                    functionFinishedAt));
            StateMachineDefinition.State nextState;
            if (failure == null) {
                nextState = transition.to();
            } else {
                nextState = transition.failureState();
            }
            currentState = nextState;
            Instant stateEnteredAt = Instant.now();
            notifySubscribers(new StateMachineEventPoint(
                    StateMachineEventPointType.STATE_ENTERED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    currentState,
                    failure,
                    stateEnteredAt));
            Instant transitionFinishedAt = Instant.now();
            StateMachineEvent event = new StateMachineEvent(
                    oldState,
                    action,
                    payload,
                    currentState,
                    failure);
            notifyListeners(event);
            if (failure == null) {
                notifySubscribers(new StateMachineEventPoint(
                        StateMachineEventPointType.TRANSITION_FINISHED,
                        oldState,
                        action,
                        payload,
                        transition.to(),
                        currentState,
                        null,
                        transitionFinishedAt));
                return event;
            }
            notifySubscribers(new StateMachineEventPoint(
                    StateMachineEventPointType.TRANSITION_FAILED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    currentState,
                    failure,
                    transitionFinishedAt));
            throw new StateTransitionFailedException(oldState, action.id(), transition.to(), currentState, failure);
        } finally {
            transitionInProgress = null;
        }
    }

    private void notifyListeners(StateMachineEvent event) {
        for (StateMachineListener listener : listeners) {
            listener.onTransition(event);
        }
    }

    private void notifySubscribers(StateMachineEventPoint event) {
        for (StateMachineEventSubscriber subscriber : subscribers) {
            try {
                subscriber.onEvent(event);
            } catch (RuntimeException ignored) {
                // Subscribers observe diagnostics and must not change transition behavior.
            }
        }
    }
}

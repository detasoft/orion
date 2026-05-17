package pro.deta.orion.lifecycle.state;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime instance of a {@link StateMachineDefinition}.
 *
 * <p>Executing by {@link ActionBinding} performs exactly one concrete transition. Executing by {@link ActionId} is the
 * default high-level mode: the machine selects the currently available binding with that id, executes it, and keeps
 * going while the same id remains available from the next state. Transitions are serialized per machine instance, and
 * observer failures are logged without changing lifecycle behavior.</p>
 */
@Slf4j
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

    public Set<StateMachineDefinition.State> states() {
        return definition.states();
    }

    public synchronized List<StateTransition<?>> availableTransitions() {
        return definition.transitionsFrom(currentState);
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

    public <A> StateTransitionEvent execute(ActionBinding<A> action, A payload) {
        Objects.requireNonNull(action, "action");
        return executeTransition(action, payload);
    }

    public <A> List<StateTransitionEvent> execute(ActionId actionId, A payload) {
        Objects.requireNonNull(actionId, "actionId");
        return executeTransitionSequence(actionId, payload);
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
            ActionBinding<A> action = (ActionBinding<A>) transition.action();
            events.add(executeTransition(action, payload));
        }
    }

    private synchronized <A> StateTransitionEvent executeTransition(ActionBinding<A> action, A payload) {
        StateTransition<A> transition = definition.transition(currentState, action)
                .orElseThrow(() -> new InvalidStateTransitionException(currentState, action.id()));
        StateMachineDefinition.State oldState = currentState;
        transitionInProgress = transition;
        try {
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_STARTED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    oldState,
                    null));
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FUNCTION_STARTED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    currentState,
                    null));
            Exception failure = null;
            try {
                transition.execute(payload);
            } catch (Exception e) {
                failure = e;
            }

            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FUNCTION_FINISHED,
                    oldState,
                    action,
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
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.AFTER_STATE_ENTERED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    currentState,
                    failure));
            StateTransitionEvent event = new StateTransitionEvent(
                    oldState,
                    action,
                    payload,
                    currentState,
                    failure);
            notifyListeners(event);
            if (failure == null) {
                notifySubscribers(new StateMachineEvent(
                        StateMachineEventType.TRANSITION_FINISHED,
                        oldState,
                        action,
                        payload,
                        transition.to(),
                        currentState,
                        null));
                return event;
            }
            notifySubscribers(new StateMachineEvent(
                    StateMachineEventType.TRANSITION_FAILED,
                    oldState,
                    action,
                    payload,
                    transition.to(),
                    currentState,
                    failure));
            throw new StateTransitionFailedException(oldState, action.id(), transition.to(), currentState, failure);
        } finally {
            transitionInProgress = null;
        }
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
}

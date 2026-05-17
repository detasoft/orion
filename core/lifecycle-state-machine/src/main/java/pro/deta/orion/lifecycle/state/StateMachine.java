package pro.deta.orion.lifecycle.state;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class StateMachine {
    private final StateMachineDefinition definition;
    private final List<StateMachineListener> listeners = new CopyOnWriteArrayList<>();
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
        transitionInProgress = transition;
        try {
            transition.execute(payload);
            currentState = transition.to();
            StateMachineEvent event = new StateMachineEvent(oldState, action, payload, currentState, null);
            notifyListeners(event);
            return event;
        } catch (Exception e) {
            currentState = transition.failureState();
            StateMachineEvent event = new StateMachineEvent(oldState, action, payload, currentState, e);
            notifyListeners(event);
            throw new StateTransitionFailedException(oldState, action.id(), transition.to(), currentState, e);
        } finally {
            transitionInProgress = null;
        }
    }

    private void notifyListeners(StateMachineEvent event) {
        for (StateMachineListener listener : listeners) {
            listener.onTransition(event);
        }
    }
}

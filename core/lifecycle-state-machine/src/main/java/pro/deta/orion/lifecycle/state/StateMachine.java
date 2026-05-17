package pro.deta.orion.lifecycle.state;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class StateMachine<S> {
    private final StateMachineDefinition<S> definition;
    private final List<StateMachineListener<S>> listeners = new CopyOnWriteArrayList<>();
    private S currentState;

    public StateMachine(StateMachineDefinition<S> definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        currentState = definition.initialState();
    }

    public static <S> StateMachine<S> create(StateMachineDefinition<S> definition) {
        return new StateMachine<>(definition);
    }

    public synchronized S currentState() {
        return currentState;
    }

    public synchronized Set<ActionBinding<?>> availableActions() {
        return definition.availableActions(currentState);
    }

    public synchronized List<StateTransition<S, ?>> availableTransitions() {
        return definition.transitionsFrom(currentState);
    }

    public synchronized List<StateTransition<S, ?>> transitionsFrom(S state) {
        return definition.transitionsFrom(state);
    }

    public synchronized StateMachineSnapshot<S> snapshot() {
        return new StateMachineSnapshot<>(
                currentState,
                definition.availableActions(currentState),
                definition.isTerminalState(currentState));
    }

    public void addListener(StateMachineListener<S> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public <A> StateMachineEvent<S> execute(ActionBinding<A> action, A payload) {
        Objects.requireNonNull(action, "action");
        return executeTransition(action, payload);
    }

    public <A> StateMachineEvent<S> execute(StateTransition<S, A> transition, A payload) {
        Objects.requireNonNull(transition, "transition");
        return executeTransition(transition.action(), payload);
    }

    private synchronized <A> StateMachineEvent<S> executeTransition(ActionBinding<A> action, A payload) {
        StateTransition<S, A> transition = definition.transition(currentState, action)
                .orElseThrow(() -> new InvalidStateTransitionException(currentState, action.id()));
        S oldState = currentState;
        try {
            transition.execute(payload);
        } catch (Exception e) {
            currentState = transition.failureState();
            StateMachineEvent<S> event = new StateMachineEvent<>(oldState, action, payload, currentState, e);
            notifyListeners(event);
            throw new StateTransitionFailedException(oldState, action.id(), transition.to(), currentState, e);
        }
        currentState = transition.to();
        StateMachineEvent<S> event = new StateMachineEvent<>(oldState, action, payload, currentState, null);
        notifyListeners(event);
        return event;
    }

    private void notifyListeners(StateMachineEvent<S> event) {
        for (StateMachineListener<S> listener : listeners) {
            listener.onTransition(event);
        }
    }
}

package pro.deta.orion.lifecycle.state;

import java.util.Objects;

/**
 * Event emitted while a state transition is being executed.
 * Subscribers receive these events for intermediate lifecycle moments such as
 * transition start, handler execution, state entry, and transition completion.
 */
public record StateMachineEvent(
        StateMachineEventType type,
        StateMachineDefinition.State from,
        ActionBinding<?> action,
        Object payload,
        StateMachineDefinition.State targetState,
        StateMachineDefinition.State currentState,
        Throwable failure) {
    public StateMachineEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetState, "targetState");
        Objects.requireNonNull(currentState, "currentState");
    }

    public boolean failed() {
        return failure != null;
    }
}

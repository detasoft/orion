package pro.deta.orion.lifecycle.state;

import java.time.Instant;
import java.util.Objects;

public record StateMachineEventPoint(
        StateMachineEventPointType type,
        StateMachineDefinition.State from,
        ActionBinding<?> action,
        Object payload,
        StateMachineDefinition.State targetState,
        StateMachineDefinition.State currentState,
        Throwable failure,
        Instant occurredAt) {
    public StateMachineEventPoint {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetState, "targetState");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public boolean failed() {
        return failure != null;
    }
}

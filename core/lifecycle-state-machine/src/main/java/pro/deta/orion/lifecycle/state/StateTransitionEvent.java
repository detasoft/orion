package pro.deta.orion.lifecycle.state;

import java.util.Objects;

/**
 * Result of a completed state transition.
 * Listeners receive this event after the machine has moved to the final state
 * chosen for the transition, including the failure state when the action fails.
 */
public record StateTransitionEvent(
        StateMachineDefinition.State from,
        ActionId action,
        Object payload,
        StateMachineDefinition.State to,
        Throwable failure) {
    public StateTransitionEvent {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(to, "to");
    }

    public boolean failed() {
        return failure != null;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("StateTransitionEvent[");
        builder.append("from=").append(from);
        builder.append(", action=").append(action);
        if (payload != Void.EMPTY) {
            builder.append(", payload=").append(payload);
        }
        builder.append(", to=").append(to);
        if (failure != null) {
            builder.append(", failure=").append(failure);
        }
        return builder.append(']').toString();
    }
}

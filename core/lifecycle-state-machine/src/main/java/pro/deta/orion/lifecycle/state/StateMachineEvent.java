package pro.deta.orion.lifecycle.state;

import java.util.Objects;

public record StateMachineEvent<S>(
        S from,
        ActionBinding<?> action,
        Object payload,
        S to,
        Throwable failure) {
    public StateMachineEvent {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(to, "to");
    }

    public boolean failed() {
        return failure != null;
    }
}

package pro.deta.orion.lifecycle.state;

import java.util.Objects;

public record StateTransition<S, A>(
        S from,
        ActionBinding<A> action,
        S to,
        S failureState) {
    public StateTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(failureState, "failureState");
    }

    void execute(A payload) throws Exception {
        action.execute(payload);
    }
}

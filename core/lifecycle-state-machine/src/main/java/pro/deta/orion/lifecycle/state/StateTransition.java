package pro.deta.orion.lifecycle.state;

import java.util.Objects;

public record StateTransition<A>(
        StateMachineDefinition.State from,
        ActionBinding<A> action,
        StateMachineDefinition.State to,
        StateMachineDefinition.State failureState) {
    public StateTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(failureState, "failureState");
    }

    void execute(A payload) throws Exception {
        action.execute(payload);
    }

    public String describe() {
        return from + " --" + action.id() + "--> " + to + " (fail -> " + failureState + ")";
    }
}

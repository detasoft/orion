package pro.deta.orion.lifecycle.state;

import java.util.Objects;

public record StateTransition<A>(
        StateMachineDefinition.State from,
        ActionId actionId,
        ActionBinding<A> action,
        StateMachineDefinition.State to,
        StateMachineDefinition.State failureState,
        StateTransitionAction transitionAction) {
    public StateTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(failureState, "failureState");
        Objects.requireNonNull(transitionAction, "transitionAction");
        if (transitionAction == StateTransitionAction.EXECUTE) {
            Objects.requireNonNull(action, "action");
        }
    }

    void execute(A payload) throws Exception {
        action.execute(payload);
    }

    public String describe() {
        String description = from + " --" + actionId + "--> " + to + " (fail -> " + failureState + ")";
        if (transitionAction != StateTransitionAction.EXECUTE) {
            return description + " [" + transitionAction + "]";
        }
        return description;
    }
}

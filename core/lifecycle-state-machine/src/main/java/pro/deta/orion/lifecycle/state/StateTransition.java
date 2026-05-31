package pro.deta.orion.lifecycle.state;

import java.util.List;
import java.util.Objects;

public record StateTransition(
        StateMachineDefinition.State from,
        ActionId actionId,
        ActionBinding<?> action,
        List<StateMachineDefinition.State> targets,
        StateTransitionResolver resolver) {
    public StateTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(action, "action");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Transition targets must not be empty");
        }
        Objects.requireNonNull(resolver, "resolver");
    }

    void register(StateMachine machine) {
        action.register(machine);
    }

    public String describe() {
        if (targets.size() == 2 && targets.contains(StandardStateDefinition.ERR)) {
            StateMachineDefinition.State successTarget = targets.getFirst().equals(StandardStateDefinition.ERR)
                    ? targets.get(1)
                    : targets.getFirst();
            return from + " --" + actionId + "--> " + successTarget + " (fail -> "
                    + StandardStateDefinition.ERR + ")";
        }
        return from + " --" + actionId + "--> " + targets;
    }
}

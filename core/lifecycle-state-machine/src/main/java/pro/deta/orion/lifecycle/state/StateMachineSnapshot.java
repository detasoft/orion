package pro.deta.orion.lifecycle.state;

import java.util.Objects;
import java.util.Set;

public record StateMachineSnapshot(
        StateMachineDefinition.State state,
        Set<ActionBinding<?>> availableActions,
        boolean terminal) {
    public StateMachineSnapshot {
        Objects.requireNonNull(state, "state");
        availableActions = Set.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
    }
}

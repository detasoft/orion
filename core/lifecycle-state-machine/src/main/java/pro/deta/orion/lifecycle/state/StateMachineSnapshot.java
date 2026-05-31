package pro.deta.orion.lifecycle.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record StateMachineSnapshot(
        StateMachineDefinition.State state,
        StateMachineDefinition.State computedState,
        Map<String, StateMachineDefinition.State> childStates,
        Set<ActionId> availableActions,
        boolean terminal,
        StateTransitionResult lastTransitionResult) {
    public StateMachineSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(computedState, "computedState");
        childStates = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(childStates, "childStates")));
        availableActions = Set.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
    }

    public StateMachineSnapshot(
            StateMachineDefinition.State state,
            Set<ActionId> availableActions,
            boolean terminal) {
        this(state, state, Map.of(), availableActions, terminal, null);
    }
}

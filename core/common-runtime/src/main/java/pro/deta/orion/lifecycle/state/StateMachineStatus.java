package pro.deta.orion.lifecycle.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recursive monitoring view of a state machine tree.
 */
public record StateMachineStatus(
        String name,
        StateMachineDefinition.State state,
        StateMachineDefinition.State computedState,
        Map<String, StateMachineStatus> children,
        Set<ActionId> availableActions,
        boolean terminal) {
    public StateMachineStatus {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(computedState, "computedState");
        children = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(children, "children")));
        availableActions = Set.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
    }
}

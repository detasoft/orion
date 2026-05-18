package pro.deta.orion.lifecycle.state;

import java.util.Map;

/**
 * Calculates the externally visible state of a machine from its physical state and observed child states.
 */
@FunctionalInterface
public interface ComputedStateResolver {
    StateMachineDefinition.State resolve(
            StateMachineDefinition.State physicalState,
            Map<String, StateMachineDefinition.State> childStates);
}

package pro.deta.orion.lifecycle.state;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AggregateStateMachine implements AutoCloseable {
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    public AggregateStateMachine(StateMachineDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        stateMachine = definition.newStateMachine();
    }

    public StateMachineDefinition definition() {
        return definition;
    }

    public StateMachine stateMachine() {
        return stateMachine;
    }

    public StateMachineDefinition.State currentState() {
        return stateMachine.currentState();
    }

    public StateMachineDefinition.State computedState() {
        return stateMachine.computedState();
    }

    public Map<String, StateMachineDefinition.State> childStates() {
        return stateMachine.childStates();
    }

    public Set<ActionId> availableActions() {
        return stateMachine.availableActions();
    }

    public List<StateTransitionResult> execute(ActionId actionId) {
        return stateMachine.execute(actionId, Void.EMPTY);
    }

    public List<StateTransitionResult> start() {
        return execute(ActionId.START);
    }

    public List<StateTransitionResult> stop() {
        return execute(ActionId.STOP);
    }

    @Override
    public void close() {
        stateMachine.close();
    }
}

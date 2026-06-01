package pro.deta.orion.lifecycle.state;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AggregateStateMachine {
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

    public Map<String, StateMachineStatus> childStatuses() {
        return stateMachine.childStatuses();
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

}

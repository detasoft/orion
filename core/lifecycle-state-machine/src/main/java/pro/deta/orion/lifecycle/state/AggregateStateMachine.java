package pro.deta.orion.lifecycle.state;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    @TestOnly
    StateMachine stateMachine() {
        return stateMachine;
    }

    public String name() {
        return stateMachine.name();
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

    public StateMachine machine(String name) {
        return stateMachine.machine(name);
    }

    public Optional<StateMachine> findMachine(String name) {
        return stateMachine.findMachine(name);
    }

    public Set<ActionId> availableActions() {
        return stateMachine.availableActions();
    }

    public Set<StateMachineDefinition.State> states() {
        return stateMachine.states();
    }

    public List<StateTransition> availableTransitions() {
        return stateMachine.availableTransitions();
    }

    public StateMachineStatus status() {
        return stateMachine.status();
    }

    public StateTransitionResult lastTransitionResult() {
        return stateMachine.lastTransitionResult();
    }

    public String describe() {
        return stateMachine.describe();
    }

    public String describeStatus() {
        return stateMachine.describeStatus();
    }

    public StateMachineSubscription subscribe(StateMachineEventSubscriber subscriber) {
        return stateMachine.subscribe(subscriber);
    }

    public <A> StateTransitionResult execute(ActionBinding<A> action, A payload) {
        return stateMachine.execute(action, payload);
    }

    public <A> List<StateTransitionResult> execute(ActionId actionId, A payload) {
        return stateMachine.execute(actionId, payload);
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

package pro.deta.orion.lifecycle.state;

public class StateTransitionFailedException extends RuntimeException {
    private final StateMachineDefinition.State from;
    private final String action;
    private final StateMachineDefinition.State intendedState;
    private final StateMachineDefinition.State currentState;

    public StateTransitionFailedException(
            StateMachineDefinition.State from,
            String action,
            StateMachineDefinition.State intendedState,
            StateMachineDefinition.State currentState,
            Throwable cause) {
        super("Action " + action + " failed while moving from " + from + " to " + intendedState, cause);
        this.from = from;
        this.action = action;
        this.intendedState = intendedState;
        this.currentState = currentState;
    }

    public StateMachineDefinition.State from() {
        return from;
    }

    public String action() {
        return action;
    }

    public StateMachineDefinition.State intendedState() {
        return intendedState;
    }

    public StateMachineDefinition.State currentState() {
        return currentState;
    }
}

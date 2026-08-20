package pro.deta.orion.lifecycle.state;

public class StateTransitionFailedException extends RuntimeException {
    private final StateMachineDefinition.State from;
    private final ActionId action;
    private final StateMachineDefinition.State intendedState;
    private final StateMachineDefinition.State currentState;
    private final StateTransitionResult result;

    public StateTransitionFailedException(StateTransitionResult result) {
        this(result.from(), result.action(), result.to(), result.to(), result.failure(), result);
    }

    public StateTransitionFailedException(
            StateMachineDefinition.State from,
            ActionId action,
            StateMachineDefinition.State intendedState,
            StateMachineDefinition.State currentState,
            Throwable cause) {
        this(from, action, intendedState, currentState, cause, null);
    }

    private StateTransitionFailedException(
            StateMachineDefinition.State from,
            ActionId action,
            StateMachineDefinition.State intendedState,
            StateMachineDefinition.State currentState,
            Throwable cause,
            StateTransitionResult result) {
        super("Action " + action + " failed while moving from " + from + " to " + intendedState, cause);
        this.from = from;
        this.action = action;
        this.intendedState = intendedState;
        this.currentState = currentState;
        this.result = result;
    }

    public StateMachineDefinition.State from() {
        return from;
    }

    public ActionId action() {
        return action;
    }

    public StateMachineDefinition.State intendedState() {
        return intendedState;
    }

    public StateMachineDefinition.State currentState() {
        return currentState;
    }

    public StateTransitionResult result() {
        return result;
    }
}

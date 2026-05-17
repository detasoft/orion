package pro.deta.orion.lifecycle.state;

public class StateTransitionFailedException extends RuntimeException {
    private final Object from;
    private final ActionId action;
    private final Object intendedState;
    private final Object currentState;

    public StateTransitionFailedException(
            Object from,
            ActionId action,
            Object intendedState,
            Object currentState,
            Throwable cause) {
        super("Action " + action + " failed while moving from " + from + " to " + intendedState, cause);
        this.from = from;
        this.action = action;
        this.intendedState = intendedState;
        this.currentState = currentState;
    }

    public Object from() {
        return from;
    }

    public ActionId action() {
        return action;
    }

    public Object intendedState() {
        return intendedState;
    }

    public Object currentState() {
        return currentState;
    }
}

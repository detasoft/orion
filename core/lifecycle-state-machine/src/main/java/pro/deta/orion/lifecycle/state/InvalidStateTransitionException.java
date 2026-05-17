package pro.deta.orion.lifecycle.state;

public class InvalidStateTransitionException extends IllegalStateException {
    public InvalidStateTransitionException(Object state, ActionId action) {
        super("Action " + action + " is not available from state " + state);
    }
}

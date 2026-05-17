package pro.deta.orion.lifecycle.state;

public class InvalidStateTransitionException extends IllegalStateException {
    public InvalidStateTransitionException(StateMachineDefinition.State state, String action) {
        super("Action " + action + " is not available from state " + state);
    }
}

package pro.deta.orion.lifecycle.state;

/**
 * Defines whether a selected transition runs its own handler or propagates the same action id to child machines.
 */
public enum StateTransitionAction {
    EXECUTE,
    PROPAGATE_SEQUENTIAL,
    PROPAGATE_PARALLEL
}

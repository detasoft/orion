package pro.deta.orion.lifecycle.state;

/**
 * Ordered lifecycle events emitted for a single state transition.
 *
 * Successful transition order:
 * TRANSITION_STARTED -> TRANSITION_FUNCTION_STARTED -> TRANSITION_FUNCTION_FINISHED
 * -> AFTER_STATE_ENTERED -> TRANSITION_FINISHED.
 *
 * Failed action order:
 * TRANSITION_STARTED -> TRANSITION_FUNCTION_STARTED -> TRANSITION_FUNCTION_FINISHED
 * -> AFTER_STATE_ENTERED -> TRANSITION_FAILED.
 *
 * TRANSITION_FUNCTION_FINISHED is emitted before the machine changes state, so
 * currentState still points to the source state and targetState points to the
 * first configured target. AFTER_STATE_ENTERED and the terminal event are
 * emitted after state resolution; their targetState and currentState both point
 * to the selected state, including the configured failure state when the action
 * fails. Invalid transitions are rejected before any event is emitted.
 * Subscribers observe events synchronously, and subscriber failures are logged
 * but do not change transition behavior.
 */
public enum StateMachineEventType {
    TRANSITION_STARTED,
    TRANSITION_FUNCTION_STARTED,
    TRANSITION_FUNCTION_FINISHED,
    AFTER_STATE_ENTERED,
    TRANSITION_FINISHED,
    TRANSITION_FAILED
}

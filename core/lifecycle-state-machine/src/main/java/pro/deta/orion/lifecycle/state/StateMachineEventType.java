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
 * currentState still points to the source state. AFTER_STATE_ENTERED is emitted
 * after currentState has been updated; on failure it points to the configured
 * failure state while targetState still names the intended success state.
 * Invalid transitions are rejected before any event is emitted. Subscribers
 * observe events synchronously, and subscriber failures are logged but do not
 * change transition behavior.
 */
public enum StateMachineEventType {
    TRANSITION_STARTED,
    TRANSITION_FUNCTION_STARTED,
    TRANSITION_FUNCTION_FINISHED,
    AFTER_STATE_ENTERED,
    TRANSITION_FINISHED,
    TRANSITION_FAILED
}

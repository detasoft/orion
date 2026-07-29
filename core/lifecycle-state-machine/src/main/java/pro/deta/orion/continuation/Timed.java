package pro.deta.orion.continuation;

/**
 * A class declares a timeout.
 */
public interface Timed {
    default long idleTimeoutNanos() {
        return -1;
    }
    default long runtimeTimeoutNanos() {
        return -1;
    }
}

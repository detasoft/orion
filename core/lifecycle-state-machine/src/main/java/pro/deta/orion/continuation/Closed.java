package pro.deta.orion.continuation;

/**
 * A continuation that holds resources released when the runtime transitions away from it.
 */
public interface Closed {
    void close();
}

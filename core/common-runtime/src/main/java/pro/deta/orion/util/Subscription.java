package pro.deta.orion.util;

/** Handle for removing a registered event listener. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}

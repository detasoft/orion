package pro.deta.orion.event;

@FunctionalInterface
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}

package pro.deta.orion.lifecycle.state;

@FunctionalInterface
public interface StateMachineSubscription extends AutoCloseable {
    @Override
    void close();
}

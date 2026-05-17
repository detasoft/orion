package pro.deta.orion.lifecycle.state;

@FunctionalInterface
public interface LifecycleActionHandler<A> {
    void execute(A action) throws Exception;
}

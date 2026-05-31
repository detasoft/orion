package pro.deta.orion.lifecycle.state;

@FunctionalInterface
public interface LifecycleActionHandler<A> {
    Object execute(A action) throws Exception;
}

package pro.deta.orion.lifecycle.state;

@FunctionalInterface
public interface StateMachineListener<S> {
    void onTransition(StateMachineEvent<S> event);
}

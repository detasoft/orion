package pro.deta.orion.lifecycle.state;

@FunctionalInterface
public interface StateMachineEventSubscriber {
    void onEvent(StateMachineEventPoint event);
}

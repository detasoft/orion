package pro.deta.orion.lifecycle.state;

@FunctionalInterface
public interface StateMachineListener {
    void onTransition(StateTransitionResult result);
}

package pro.deta.orion.lifecycle.state;

@FunctionalInterface
public interface StateTransitionResolver {
    StateMachineDefinition.State resolve(StateTransitionResult transitionResult);
}

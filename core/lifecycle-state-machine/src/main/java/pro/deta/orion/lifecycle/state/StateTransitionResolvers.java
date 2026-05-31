package pro.deta.orion.lifecycle.state;

import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;

final class StateTransitionResolvers {
    private StateTransitionResolvers() {
    }

    static StateMachineDefinition.State defaultState(StateTransitionResult result) {
        if (result.failed()) {
            if (result.targets().contains(ERR)) {
                return ERR;
            }
            throw new IllegalStateException(
                    "Action " + result.action() + " failed but transition targets do not include " + ERR,
                    result.failure());
        }

        List<StateMachineDefinition.State> successTargets = new ArrayList<>();
        for (StateMachineDefinition.State target : result.targets()) {
            if (!ERR.equals(target)) {
                successTargets.add(target);
            }
        }
        if (successTargets.size() == 1) {
            return successTargets.getFirst();
        }
        if (successTargets.isEmpty()) {
            throw new IllegalStateException("Transition " + result.action() + " has no success target states");
        }
        throw new IllegalStateException(
                "Transition " + result.action() + " has multiple success target states " + successTargets
                        + "; configure post resolver");
    }
}

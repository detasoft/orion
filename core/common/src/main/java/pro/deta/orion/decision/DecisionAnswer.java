package pro.deta.orion.decision;

import pro.deta.orion.schema.orion.PrincipalAddress;

import java.util.Objects;

/**
 * A user's selected action and identity, delivered to the operation awaiting a decision.
 * The action is an index in the decision's immutable list of titled actions.
 */
public record DecisionAnswer(int action, PrincipalAddress actor) {
    public DecisionAnswer {
        Objects.requireNonNull(actor, "actor");
    }
}

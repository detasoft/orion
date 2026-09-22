package pro.deta.orion.decision;

import pro.deta.orion.schema.orion.PrincipalAddress;

import java.util.Objects;

/**
 * A user's selected action and identity, delivered to the operation awaiting a decision.
 * The requesting operation defines the action identifiers and their meaning.
 */
public record Decision(String action, PrincipalAddress actor) {
    public Decision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        if (action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }
}

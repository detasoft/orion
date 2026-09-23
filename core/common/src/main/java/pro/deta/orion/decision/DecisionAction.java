package pro.deta.orion.decision;

import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.util.Objects;
import java.util.function.Function;

/** A titled action whose handler captures its own dependencies and receives the authenticated responder. */
public record DecisionAction(String title, Function<PrincipalAddress, Result<Void>> handler) {
    public DecisionAction {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(handler, "handler");
        if (title.isBlank()) throw new IllegalArgumentException("title must not be blank");
    }
}

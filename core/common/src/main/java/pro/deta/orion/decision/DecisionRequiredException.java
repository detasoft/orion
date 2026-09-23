package pro.deta.orion.decision;

import java.util.Objects;

/** Carries a ready resolution while retaining the original failure in the standard cause chain. */
public final class DecisionRequiredException extends RuntimeException implements Decisionable {
    private final Decision decision;

    public DecisionRequiredException(Decision decision, Throwable cause) {
        super("Operation requires a decision", Objects.requireNonNull(cause, "cause"));
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    @Override
    public Decision decision() {
        return decision;
    }
}

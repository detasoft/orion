package pro.deta.orion.auth.check;

import java.util.Objects;

/**
 * Result of applying an access rule to a context and resource. The reason is part of the model so callers
 * and logs can explain both allowed and denied decisions without reverse-engineering rule internals.
 */
public record AccessDecision(boolean allowed, String reason) {
    public AccessDecision {
        Objects.requireNonNull(reason, "reason");
    }

    public static AccessDecision allow(String reason) {
        return new AccessDecision(true, reason);
    }

    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, reason);
    }
}

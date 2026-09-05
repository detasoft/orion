package pro.deta.orion.schema.orion;

import pro.deta.orion.schema.acl.AccessControl;

import java.util.List;
import java.util.Objects;

public record ScopedGrant(
        GrantId id,
        Effect effect,
        List<AccessControl.GrantExpression> expressions) {
    public ScopedGrant {
        Objects.requireNonNull(id, "grant id");
        Objects.requireNonNull(effect, "grant effect");
        Objects.requireNonNull(expressions, "grant expressions");
        for (AccessControl.GrantExpression expression : expressions) {
            Objects.requireNonNull(expression, "grant expression");
        }
        expressions = List.copyOf(expressions);
    }

    public enum Effect {
        ALLOW,
        DENY
    }
}

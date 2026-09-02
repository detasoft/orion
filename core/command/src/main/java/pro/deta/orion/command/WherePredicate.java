package pro.deta.orion.command;

import java.util.Objects;

public record WherePredicate(String field, Operator operator, String value) {
    public WherePredicate {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value");
        if (field.isEmpty()) {
            throw new IllegalArgumentException("field must not be empty");
        }
    }

    public enum Operator {
        EQUALS,
        NOT_EQUALS
    }
}

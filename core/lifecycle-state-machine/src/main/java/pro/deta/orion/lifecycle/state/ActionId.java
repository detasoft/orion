package pro.deta.orion.lifecycle.state;

import java.util.Objects;

public record ActionId(String value) {
    public ActionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Action id must not be blank");
        }
    }

    public static ActionId of(Class<?> actionType) {
        Objects.requireNonNull(actionType, "actionType");
        return new ActionId(actionType.getName());
    }

    @Override
    public String toString() {
        return value;
    }
}

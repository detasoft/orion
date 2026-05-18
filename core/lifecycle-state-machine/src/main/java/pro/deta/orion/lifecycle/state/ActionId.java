package pro.deta.orion.lifecycle.state;

import java.util.Objects;

/**
 * Stable semantic identity of a lifecycle action shared by different action bindings.
 *
 * <p>Different states may expose different {@link ActionBinding} instances with the same id. Executing a machine by
 * {@code ActionId} follows the currently available binding and then continues while the same id is available from the
 * next state. This makes ids suitable for high-level actions such as {@code START} and {@code STOP}, while each binding
 * still owns the concrete typed handler for one state transition. Parent propagation transitions also use this id to
 * find matching child actions.</p>
 */
public final class ActionId {
    public static final ActionId START = new ActionId("START");
    public static final ActionId STOP = new ActionId("STOP");

    private final String value;

    private ActionId(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Action id must not be blank");
        }
    }

    public static ActionId of(String value) {
        if (START.value.equals(value)) {
            return START;
        }
        if (STOP.value.equals(value)) {
            return STOP;
        }
        return new ActionId(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActionId actionId)) {
            return false;
        }
        return value.equals(actionId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}

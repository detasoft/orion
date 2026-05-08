package pro.deta.orion.lifecycle.task;

import java.util.Objects;

public record LifecycleTaskId(String value) {
    public LifecycleTaskId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Lifecycle task id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

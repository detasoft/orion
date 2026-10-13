package pro.deta.orion.config;

import java.util.Objects;

public sealed interface OrionRevision {
    record Absent() implements OrionRevision {}

    record Stored(String value) implements OrionRevision {
        public Stored {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) {
                throw new IllegalArgumentException("Revision must not be blank");
            }
        }
    }
}



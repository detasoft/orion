package pro.deta.orion.util;

import java.util.Objects;

public sealed interface VcRevision {
    record Absent() implements VcRevision {}

    record Stored(String value) implements VcRevision {
        public Stored {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) {
                throw new IllegalArgumentException("Revision must not be blank");
            }
        }
    }
}



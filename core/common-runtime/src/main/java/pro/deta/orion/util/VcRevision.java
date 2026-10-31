package pro.deta.orion.util;

import java.util.Objects;

public sealed interface VcRevision {
    sealed interface ReadTarget permits Latest, Stored {}

    record Latest() implements ReadTarget {}

    record Absent() implements VcRevision {}

    record Stored(String value) implements VcRevision, ReadTarget {
        public Stored {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) {
                throw new IllegalArgumentException("Revision must not be blank");
            }
        }
    }
}



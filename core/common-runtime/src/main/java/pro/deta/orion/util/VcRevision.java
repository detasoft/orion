package pro.deta.orion.util;

import java.util.Objects;

public sealed interface VcRevision {
    sealed interface ReadTarget permits Latest, Ref, Stored {}

    record Latest() implements ReadTarget {}

    record Ref(String value) implements ReadTarget {
        public Ref {
            Objects.requireNonNull(value, "value");
            if (value.isBlank()) throw new IllegalArgumentException("Ref must not be blank");
        }
    }

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



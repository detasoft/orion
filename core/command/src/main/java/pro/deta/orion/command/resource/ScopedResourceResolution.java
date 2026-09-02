package pro.deta.orion.command.resource;

import java.util.List;
import java.util.Objects;

public sealed interface ScopedResourceResolution<T> {
    record Resolved<T>(ScopedResourceCandidate<T> candidate) implements ScopedResourceResolution<T> {
        public Resolved {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    record Missing<T>() implements ScopedResourceResolution<T> {}

    record Ambiguous<T>(List<String> candidateIds) implements ScopedResourceResolution<T> {
        public Ambiguous {
            Objects.requireNonNull(candidateIds, "candidateIds");
            candidateIds = List.copyOf(candidateIds);
        }
    }
}

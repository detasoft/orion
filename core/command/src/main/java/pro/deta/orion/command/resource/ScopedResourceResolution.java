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

    record Unavailable<T>(String source) implements ScopedResourceResolution<T> {
        public Unavailable {
            source = requireSource(source);
        }
    }

    record AccessDenied<T>(String reason) implements ScopedResourceResolution<T> {
        public AccessDenied {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    record Failed<T>(String source, Throwable throwable) implements ScopedResourceResolution<T> {
        public Failed {
            source = requireSource(source);
            Objects.requireNonNull(throwable, "throwable");
        }
    }

    private static String requireSource(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        return source;
    }
}

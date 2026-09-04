package pro.deta.orion.command.resource;

import java.util.List;
import java.util.Objects;

public sealed interface ScopedResourceCatalogResult<T> {
    record Available<T>(List<ScopedResourceCandidate<T>> candidates)
            implements ScopedResourceCatalogResult<T> {
        public Available {
            Objects.requireNonNull(candidates, "candidates");
            candidates = List.copyOf(candidates);
        }
    }

    record Unavailable<T>(String source) implements ScopedResourceCatalogResult<T> {
        public Unavailable {
            source = requireSource(source);
        }
    }

    record AccessDenied<T>(String reason) implements ScopedResourceCatalogResult<T> {
        public AccessDenied {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    record Failed<T>(String source, Throwable throwable) implements ScopedResourceCatalogResult<T> {
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

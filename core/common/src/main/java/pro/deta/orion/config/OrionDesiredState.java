package pro.deta.orion.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.schema.orion.OrionDocument;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Singleton
public final class OrionDesiredState {
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    @Inject
    public OrionDesiredState() {
    }

    public Snapshot current() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("Orion desired state has not been published");
        }
        return snapshot;
    }

    public void publish(OrionDocument document, Optional<String> revision) {
        current.set(new Snapshot(document, revision));
    }

    public record Snapshot(OrionDocument document, Optional<String> revision) {
        public Snapshot {
            Objects.requireNonNull(document, "document");
            revision = Objects.requireNonNullElseGet(revision, Optional::empty);
        }
    }
}

package pro.deta.orion.config;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

/** A decoded configuration snapshot read at the revision published by its storage. */
public final class ConfigurationChanged extends OrionEvent {
    private final OrionVersionedConfigurationStorage storage;
    private final OrionDocumentRevision snapshot;

    public ConfigurationChanged(OrionVersionedConfigurationStorage storage, OrionDocumentRevision snapshot) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public OrionVersionedConfigurationStorage storage() {
        return storage;
    }

    public OrionDocumentRevision snapshot() {
        return snapshot;
    }
}
